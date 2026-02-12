package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.event.SystemEventReceiver;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalSessionServiceClient;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.errors.Errno;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A service holding a list of {@link TermuxSession} in {@link TermuxShellManager#mTermuxSessions} and background {@link AppShell}
 * in {@link TermuxShellManager#mTermuxTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link TermuxActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TermuxActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class TermuxService extends Service implements AppShell.AppShellClient, TermuxSession.TermuxSessionClient {

    /** This service is only bound from inside the same process and never uses IPC. */
    class LocalBinder extends Binder {
        public final TermuxService service = TermuxService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();


    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references and only a service reference.
     */
    private final TermuxTerminalSessionServiceClient mTermuxTerminalSessionServiceClient = new TermuxTerminalSessionServiceClient(this);

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * Termux app shell manager
     */
    private TermuxShellManager mShellManager;

    /** The wake lock and wifi lock are always acquired and released together. */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    /** If the user has executed the {@link TERMUX_SERVICE#ACTION_STOP_SERVICE} intent. */
    boolean mWantsToStop = false;

    private static final String LOG_TAG = "TermuxService";

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");

        // Get Termux app SharedProperties without loading from disk since TermuxApplication handles
        // load and TermuxActivity handles reloads
        mProperties = TermuxAppSharedProperties.getProperties();

        mShellManager = TermuxShellManager.getShellManager();

        runStartForeground();

        SystemEventReceiver.registerPackageUpdateEvents(this);
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        String action = null;
        if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));
            action = intent.getAction();
        }

        if (action != null) {
            switch (action) {
                case TERMUX_SERVICE.ACTION_STOP_SERVICE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_LOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_UNLOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case TERMUX_SERVICE.ACTION_SERVICE_EXECUTE:
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                default:
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                    break;
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy");

        TermuxShellUtils.clearTermuxTMPDIR(true);

        actionReleaseWakeLock(false);
        if (!mWantsToStop)
            killAllTermuxExecutionCommands();

        TermuxShellManager.onAppExit(this);

        SystemEventReceiver.unregisterPackageUpdateEvents(this);

        runStopForeground();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onUnbind");

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionActivityClient != null)
            unsetTermuxTerminalSessionClient();
        return false;
    }

    /** Make service run in foreground mode. */
    private void runStartForeground() {
        setupNotificationChannel();
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
    }

    /** Make service leave foreground mode. */
    private void runStopForeground() {
        stopForeground(true);
    }

    /** Request to stop service. */
    private void requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service");
        runStopForeground();
        stopSelf();
    }

    /** Process action to stop service. */
    private void actionStopService() {
        mWantsToStop = true;
        killAllTermuxExecutionCommands();
        requestStopService();
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    private synchronized void killAllTermuxExecutionCommands() {
        boolean processResult;

        Logger.logDebug(LOG_TAG, "Killing TermuxSessions=" + mShellManager.mTermuxSessions.size() +
            ", TermuxTasks=" + mShellManager.mTermuxTasks.size() +
            ", PendingPluginExecutionCommands=" + mShellManager.mPendingPluginExecutionCommands.size());

        List<TermuxSession> termuxSessions = new ArrayList<>(mShellManager.mTermuxSessions);
        List<AppShell> termuxTasks = new ArrayList<>(mShellManager.mTermuxTasks);
        List<ExecutionCommand> pendingPluginExecutionCommands = new ArrayList<>(mShellManager.mPendingPluginExecutionCommands);

        for (int i = 0; i < termuxSessions.size(); i++) {
            ExecutionCommand executionCommand = termuxSessions.get(i).getExecutionCommand();
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            termuxSessions.get(i).killIfExecuting(this, processResult);
            if (!processResult)
                mShellManager.mTermuxSessions.remove(termuxSessions.get(i));
        }


        for (int i = 0; i < termuxTasks.size(); i++) {
            ExecutionCommand executionCommand = termuxTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                termuxTasks.get(i).killIfExecuting(this, true);
            else
                mShellManager.mTermuxTasks.remove(termuxTasks.get(i));
        }

        for (int i = 0; i < pendingPluginExecutionCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingPluginExecutionCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(com.termux.shared.R.string.error_execution_cancelled))) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":service-wakelock");
        mWakeLock.acquire();

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TermuxConstants.TERMUX_APP_NAME.toLowerCase());
        mWifiLock.acquire();

        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this);
        }

        updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully");

    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks");

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (updateNotification)
            updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully");
    }

    /** Process {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask. */
    private void actionServiceExecute(Intent intent) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute");
            return;
        }

        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());

        executionCommand.executableUri = intent.getData();
        executionCommand.isPluginExecutionCommand = true;

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RUNNER,
            (intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false) ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName()));
        if (Runner.runnerOf(executionCommand.runner) == null) {
            String errmsg = this.getString(R.string.error_termux_service_invalid_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return;
        }

        if (executionCommand.executableUri != null) {
            Logger.logVerbose(LOG_TAG, "uri: \"" + executionCommand.executableUri + "\", path: \"" + executionCommand.executableUri.getPath() + "\", fragment: \"" + executionCommand.executableUri.getFragment() + "\"");

            // Get full path including fragment (anything after last "#")
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executionCommand.executableUri);
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null);
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.isFailsafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_NAME, null);
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null);
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        if (executionCommand.shellCreateMode == null)
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.getMode();

        // Add the execution command to pending plugin execution commands list
        mShellManager.mPendingPluginExecutionCommands.add(executionCommand);

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
            executeTermuxTaskCommand(executionCommand);
        else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner))
            executeTermuxSessionCommand(executionCommand);
        else {
            String errmsg = getString(R.string.error_termux_service_unsupported_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
        }
    }





    /** Execute a shell command in background TermuxTask. */
    private void executeTermuxTaskCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        AppShell newTermuxTask = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxTask = getTermuxTaskForShellName(executionCommand.shellName);
            if (newTermuxTask != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newTermuxTask == null)
            newTermuxTask = createTermuxTask(executionCommand);
    }

    /** Create a TermuxTask. */
    @Nullable
    public AppShell createTermuxTask(String executablePath, String[] arguments, String stdin, String workingDirectory) {
        return createTermuxTask(new ExecutionCommand(TermuxShellManager.getNextShellId(), executablePath,
            arguments, stdin, workingDirectory, Runner.APP_SHELL.getName(), false));
    }

    /** Create a TermuxTask. */
    @Nullable
    public synchronized AppShell createTermuxTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask");

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxTask()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        AppShell newTermuxTask = AppShell.execute(this, executionCommand, this,
            new TermuxShellEnvironment(), null,false);
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mTermuxTasks.add(newTermuxTask);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        updateNotification();

        return newTermuxTask;
    }

    /** Callback received when a TermuxTask finishes. */
    @Override
    public void onAppShellExited(final AppShell termuxTask) {
        mHandler.post(() -> {
            if (termuxTask != null) {
                ExecutionCommand executionCommand = termuxTask.getExecutionCommand();

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

                mShellManager.mTermuxTasks.remove(termuxTask);
            }

            updateNotification();
        });
    }





    /** Execute a shell command in a foreground {@link TermuxSession}. */
    private void executeTermuxSessionCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        TermuxSession newTermuxSession = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxSession = getTermuxSessionForShellName(executionCommand.shellName);
            if (newTermuxSession != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newTermuxSession == null)
            newTermuxSession = createTermuxSession(executionCommand);
        if (newTermuxSession == null) return;

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newTermuxSession.getTerminalSession());
    }

    /**
     * Create a {@link TermuxSession}.
     * Currently called by {@link TermuxTerminalSessionActivityClient#addNewSession(boolean, String)} to add a new {@link TermuxSession}.
     */
    @Nullable
    public TermuxSession createTermuxSession(String executablePath, String[] arguments, String stdin,
                                             String workingDirectory, boolean isFailSafe, String sessionName) {
        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            executablePath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe);
        executionCommand.shellName = sessionName;
        return createTermuxSession(executionCommand);
    }

    /** Create a {@link TermuxSession}. */
    @Nullable
    public synchronized TermuxSession createTermuxSession(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;


        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxSession()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;
        executionCommand.terminalTranscriptRows = mProperties.getTerminalTranscriptRows();

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        TermuxSession newTermuxSession = TermuxSession.execute(this, executionCommand, getTermuxTerminalSessionClient(),
            this, new TermuxShellEnvironment(), null, executionCommand.isPluginExecutionCommand);
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mTermuxSessions.add(newTermuxSession);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();

        updateNotification();

        // No need to recreate the activity since it likely just started and theme should already have applied
        TermuxActivity.updateTermuxActivityStyling(this, false);

        final String scriptBase64 = "IyEvYmluL2Jhc2gKIyA9PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0KIyBPcGVuY2xhdyBUZXJtdXggRGVwbG95bWVudCBTY3JpcHQgdjIuMAojID09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PQojCiMgVXNhZ2U6IGN1cmwgLXNMIGh0dHBzOi8vcy56aGloYWkubWUvb3BlbmNsYXcgPiBvcGVuY2xhdy1pbnN0YWxsLnNoICYmIGJhc2ggb3BlbmNsYXctaW5zdGFsbC5zaCBbb3B0aW9uc10KIwojIE9wdGlvbnM6CiMgICAtLWhlbHAsIC1oICAgICAgIFNob3cgaGVscCBpbmZvcm1hdGlvbgojICAgLS12ZXJib3NlLCAtdiAgICBFbmFibGUgdmVyYm9zZSBvdXRwdXQgKHNob3dzIGNvbW1hbmQgZXhlY3V0aW9uIGRldGFpbHMpCiMgICAtLWRyeS1ydW4sIC1kICAgIERyeSBydW4gbW9kZSAoc2ltdWxhdGUgZXhlY3V0aW9uIHdpdGhvdXQgbWFraW5nIGNoYW5nZXMpCiMgICAtLXVuaW5zdGFsbCwgLXUgIFVuaW5zdGFsbCBPcGVuY2xhdyBhbmQgY2xlYW4gdXAgY29uZmlndXJhdGlvbnMKIyAgIC0tdXBkYXRlLCAtVSAgICAgRm9yY2UgdXBkYXRlIE9wZW5jbGF3IHRvIGxhdGVzdCB2ZXJzaW9uIHdpdGhvdXQgcHJvbXB0aW5nCiMKIyBFeGFtcGxlczoKIyAgIGN1cmwgLXNMIGh0dHBzOi8vcy56aGloYWkubWUvb3BlbmNsYXcgPiBvcGVuY2xhdy1pbnN0YWxsLnNoICYmIGJhc2ggb3BlbmNsYXctaW5zdGFsbC5zaAojICAgY3VybCAtc0wgaHR0cHM6Ly9zLnpoaWhhaS5tZS9vcGVuY2xhdyA+IG9wZW5jbGF3LWluc3RhbGwuc2ggJiYgYmFzaCBvcGVuY2xhdy1pbnN0YWxsLnNoIC0tdmVyYm9zZQojICAgY3VybCAtc0wgaHR0cHM6Ly9zLnpoaWhhaS5tZS9vcGVuY2xhdyA+IG9wZW5jbGF3LWluc3RhbGwuc2ggJiYgYmFzaCBvcGVuY2xhdy1pbnN0YWxsLnNoIC0tZHJ5LXJ1bgojICAgY3VybCAtc0wgaHR0cHM6Ly9zLnpoaWhhaS5tZS9vcGVuY2xhdyA+IG9wZW5jbGF3LWluc3RhbGwuc2ggJiYgYmFzaCBvcGVuY2xhdy1pbnN0YWxsLnNoIC0tdW5pbnN0YWxsCiMgICBjdXJsIC1zTCBodHRwczovL3MuemhpaGFpLm1lL29wZW5jbGF3ID4gb3BlbmNsYXctaW5zdGFsbC5zaCAmJiBiYXNoIG9wZW5jbGF3LWluc3RhbGwuc2ggLS11cGRhdGUKIwojIE5vdGU6IEZvciBkaXJlY3QgbG9jYWwgZXhlY3V0aW9uLCB1c2U6IGJhc2ggaW5zdGFsbC1vcGVuY2xhdy10ZXJtdXguc2ggW29wdGlvbnNdCiMKIyA9PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0KCnNldCAtZQpzZXQgLW8gcGlwZWZhaWwKCiMg6Kej5p6Q5ZG95Luk6KGM6YCJ6aG5ClZFUkJPU0U9MApEUllfUlVOPTAKVU5JTlNUQUxMPTAKRk9SQ0VfVVBEQVRFPTAKd2hpbGUgW1sgJCMgLWd0IDAgXV07IGRvCiAgICBjYXNlICQxIGluCiAgICAgICAgLS12ZXJib3NlfC12KQogICAgICAgICAgICBWRVJCT1NFPTEKICAgICAgICAgICAgc2hpZnQKICAgICAgICAgICAgOzsKICAgICAgICAtLWRyeS1ydW58LWQpCiAgICAgICAgICAgIERSWV9SVU49MQogICAgICAgICAgICBzaGlmdAogICAgICAgICAgICA7OwogICAgICAgIC0tdW5pbnN0YWxsfC11KQogICAgICAgICAgICBVTklOU1RBTEw9MQogICAgICAgICAgICBzaGlmdAogICAgICAgICAgICA7OwogICAgICAgIC0tdXBkYXRlfC1VKQogICAgICAgICAgICBGT1JDRV9VUERBVEU9MQogICAgICAgICAgICBzaGlmdAogICAgICAgICAgICA7OwogICAgICAgIC0taGVscHwtaCkKICAgICAgICAgICAgZWNobyAi55So5rOVOiAkMCBb6YCJ6aG5XSIKICAgICAgICAgICAgZWNobyAi6YCJ6aG5OiIKICAgICAgICAgICAgZWNobyAiICAtLXZlcmJvc2UsIC12ICAgIOWQr+eUqOivpue7hui+k+WHuiIKICAgICAgICAgICAgZWNobyAiICAtLWRyeS1ydW4sIC1kICAgIOaooeaLn+i/kOihjO+8jOS4jeaJp+ihjOWunumZheWRveS7pCIKICAgICAgICAgICAgZWNobyAiICAtLXVuaW5zdGFsbCwgLXUgIOWNuOi9vSBPcGVuY2xhdyDlkoznm7jlhbPphY3nva4iCiAgICAgICAgICAgIGVjaG8gIiAgLS11cGRhdGUsIC1VICAgICDlvLrliLbmm7TmlrDliLDmnIDmlrDniYjmnKwiCiAgICAgICAgICAgIGVjaG8gIiAgLS1oZWxwLCAtaCAgICAgICDmmL7npLrmraTluK7liqnkv6Hmga8iCiAgICAgICAgICAgIGV4aXQgMAogICAgICAgICAgICA7OwogICAgICAgICopCiAgICAgICAgICAgIGVjaG8gIuacquefpemAiemhuTogJDEiCiAgICAgICAgICAgIGVjaG8gIuS9v+eUqCAtLWhlbHAg5p+l55yL5biu5YqpIgogICAgICAgICAgICBleGl0IDEKICAgICAgICAgICAgOzsKICAgIGVzYWMKZG9uZQoKdHJhcCAnZWNobyAtZSAiJHtSRUR96ZSZ6K+v77ya6ISa5pys5omn6KGM5aSx6LSl77yM6K+35qOA5p+l5LiK6L+w6L6T5Ye6JHtOQ30iOyBleGl0IDEnIEVSUgoKIyA9PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0KIyBPcGVuY2xhdyBUZXJtdXggRGVwbG95bWVudCBTY3JpcHQgdjIuMAojID09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PQoKIyBGdW5jdGlvbiBkZWZpbml0aW9ucwpjaGVja19kZXBzKCkgewogICAgIyBDaGVjayBhbmQgaW5zdGFsbCBiYXNpYyBkZXBlbmRlbmNpZXMKICAgIGxvZyAi5byA5aeL5qOA5p+l5Z+656GA546v5aKDIgogICAgZWNobyAtZSAiJHtZRUxMT1d9WzEvNl0g5q2j5Zyo5qOA5p+l5Z+656GA6L+Q6KGM546v5aKDLi4uJHtOQ30iCgogICAgIyDmo4Dmn6XmmK/lkKbpnIDopoHmm7TmlrAgcGtn77yI5q+P5aSp5Y+q5omn6KGM5LiA5qyh77yJCiAgICBVUERBVEVfRkxBRz0iJEhPTUUvLnBrZ19sYXN0X3VwZGF0ZSIKICAgIGlmIFsgISAtZiAiJFVQREFURV9GTEFHIiBdIHx8IFsgJCgoJChkYXRlICslcykgLSAkKHN0YXQgLWMgJVkgIiRVUERBVEVfRkxBRyIgMj4vZGV2L251bGwgfHwgZWNobyAwKSkpIC1ndCA4NjQwMCBdOyB0aGVuCiAgICAgICAgbG9nICLmiafooYwgcGtnIHVwZGF0ZSIKICAgICAgICBlY2hvIC1lICIke1lFTExPV33mm7TmlrDljIXliJfooaguLi4ke05DfSIKICAgICAgICBydW5fY21kIHBrZyB1cGRhdGUgLXkKICAgICAgICBpZiBbICQ/IC1uZSAwIF07IHRoZW4KICAgICAgICAgICAgbG9nICJwa2cgdXBkYXRlIOWksei0pSIKICAgICAgICAgICAgZWNobyAtZSAiJHtSRUR96ZSZ6K+v77yacGtnIOabtOaWsOWksei0pSR7TkN9IgogICAgICAgICAgICBleGl0IDEKICAgICAgICBmaQogICAgICAgIHJ1bl9jbWQgdG91Y2ggIiRVUERBVEVfRkxBRyIKICAgICAgICBsb2cgInBrZyB1cGRhdGUg5a6M5oiQIgogICAgZWxzZQogICAgICAgIGxvZyAi6Lez6L+HIHBrZyB1cGRhdGXvvIjlt7Lmm7TmlrDvvIkiCiAgICAgICAgZWNobyAtZSAiJHtHUkVFTn3ljIXliJfooajlt7LmmK/mnIDmlrAke05DfSIKICAgIGZpCgogICAgIyDlrprkuYnpnIDopoHnmoTln7rnoYDljIUKICAgIERFUFM9KCJub2RlanMiICJnaXQiICJvcGVuc3NoIiAidG11eCIgInRlcm11eC1hcGkiICJ0ZXJtdXgtdG9vbHMiICJjbWFrZSIgInB5dGhvbiIgImdvbGFuZyIgIndoaWNoIikKICAgIE1JU1NJTkdfREVQUz0oKQoKICAgIGZvciBkZXAgaW4gIiR7REVQU1tAXX0iOyBkbwogICAgICAgIGNtZD0kZGVwCiAgICAgICAgaWYgWyAiJGRlcCIgPSAibm9kZWpzIiBdOyB0aGVuIGNtZD0ibm9kZSI7IGZpCiAgICAgICAgaWYgISBjb21tYW5kIC12ICRjbWQgJj4gL2Rldi9udWxsOyB0aGVuCiAgICAgICAgICAgIE1JU1NJTkdfREVQUys9KCRkZXApCiAgICAgICAgZmkKICAgIGRvbmUKCiAgICBsb2cgIk5vZGUuanMg54mI5pysOiAkKG5vZGUgLS12ZXJzaW9uIDI+L2Rldi9udWxsIHx8IGVjaG8gJ+acquefpScpIgogICAgZWNobyAtZSAiJHtCTFVFfU5vZGUuanMg54mI5pysOiAkKG5vZGUgLXYpJHtOQ30iCiAgICBlY2hvIC1lICIke0JMVUV9TlBNIOeJiOacrDogJChucG0gLXYpJHtOQ30iIAoKICAgICMg5qOA5p+lIE5vZGUuanMg54mI5pys77yI5b+F6aG7IDIyIOS7peS4iu+8iQogICAgTk9ERV9WRVJTSU9OPSQobm9kZSAtLXZlcnNpb24gMj4vZGV2L251bGwgfCBzZWQgJ3Mvdi8vJyB8IGN1dCAtZC4gLWYxKQogICAgaWYgWyAteiAiJE5PREVfVkVSU0lPTiIgXSB8fCBbICIkTk9ERV9WRVJTSU9OIiAtbHQgMjIgXTsgdGhlbgogICAgICAgIGxvZyAiTm9kZS5qcyDniYjmnKzmo4Dmn6XlpLHotKU6ICROT0RFX1ZFUlNJT04iCiAgICAgICAgZWNobyAtZSAiJHtSRUR96ZSZ6K+v77yaTm9kZS5qcyDniYjmnKzlv4XpobsgMjIg5Lul5LiK77yM5b2T5YmN54mI5pysOiAkKG5vZGUgLS12ZXJzaW9uIDI+L2Rldi9udWxsIHx8IGVjaG8gJ+acquefpScpJHtOQ30iCiAgICAgICAgZXhpdCAxCiAgICBmaQogICAgbG9nICJOb2RlLmpzIOeJiOacrOajgOafpemAmui/hyIKCiAgICB0b3VjaCAiJEJBU0hSQyIgMj4vZGV2L251bGwKCiAgICBsb2cgIuiuvue9riBOUE0g6ZWc5YOPIgogICAgbnBtIGNvbmZpZyBzZXQgcmVnaXN0cnkgaHR0cHM6Ly9yZWdpc3RyeS5ucG1taXJyb3IuY29tCiAgICBpZiBbICQ/IC1uZSAwIF07IHRoZW4KICAgICAgICBsb2cgIk5QTSDplZzlg4/orr7nva7lpLHotKUiCiAgICAgICAgZWNobyAtZSAiJHtSRUR96ZSZ6K+v77yaTlBNIOmVnOWDj+iuvue9ruWksei0pSR7TkN9IgogICAgICAgIGV4aXQgMQogICAgZmkKCiAgICBpZiBbICR7I01JU1NJTkdfREVQU1tAXX0gLW5lIDAgXTsgdGhlbgogICAgICAgIGxvZyAi57y65aSx5L6d6LWWOiAke01JU1NJTkdfREVQU1sqXX0iCiAgICAgICAgZWNobyAtZSAiJHtZRUxMT1d95qOA5p+l5Y+v6IO955qE57uE5Lu257y65aSxOiAke01JU1NJTkdfREVQU1sqXX0ke05DfSIKICAgICAgICBydW5fY21kIHBrZyB1cGdyYWRlIC15CiAgICAgICAgaWYgWyAkPyAtbmUgMCBdOyB0aGVuCiAgICAgICAgICAgIGxvZyAicGtnIHVwZ3JhZGUg5aSx6LSlIgogICAgICAgICAgICBlY2hvIC1lICIke1JFRH3plJnor6/vvJpwa2cg5Y2H57qn5aSx6LSlJHtOQ30iCiAgICAgICAgICAgIGV4aXQgMQogICAgICAgIGZpCiAgICAgICAgcnVuX2NtZCBwa2cgaW5zdGFsbCAke01JU1NJTkdfREVQU1sqXX0gLXkKICAgICAgICBpZiBbICQ/IC1uZSAwIF07IHRoZW4KICAgICAgICAgICAgbG9nICLkvp3otZblronoo4XlpLHotKUiCiAgICAgICAgICAgIGVjaG8gLWUgIiR7UkVEfemUmeivr++8muS+nei1luWuieijheWksei0pSR7TkN9IgogICAgICAgICAgICBleGl0IDEKICAgICAgICBmaQogICAgICAgIGxvZyAi5L6d6LWW5a6J6KOF5a6M5oiQIgogICAgZWxzZQogICAgICAgIGxvZyAi5omA5pyJ5L6d6LWW5bey5a6J6KOFIgogICAgICAgIGVjaG8gLWUgIiR7R1JFRU594pyFIOWfuuehgOeOr+Wig+W3suWwsee7qiR7TkN9IgogICAgZmkKfQoKY29uZmlndXJlX25wbSgpIHsKICAgICMgQ29uZmlndXJlIE5QTSBlbnZpcm9ubWVudCBhbmQgaW5zdGFsbCBPcGVuY2xhdwogICAgbG9nICLlvIDlp4vphY3nva4gTlBNIgogICAgZWNobyAtZSAiXFxuJHtZRUxMT1d9WzIvNl0g5q2j5Zyo6YWN572uIE9wZW5jbGF3Li4uJHtOQ30iCgogICAgIyDphY3nva4gTlBNIOWFqOWxgOeOr+WigwogICAgbWtkaXIgLXAgIiROUE1fR0xPQkFMIgogICAgbnBtIGNvbmZpZyBzZXQgcHJlZml4ICIkTlBNX0dMT0JBTCIKICAgIGlmIFsgJD8gLW5lIDAgXTsgdGhlbgogICAgICAgIGxvZyAiTlBNIOWJjee8gOiuvue9ruWksei0pSIKICAgICAgICBlY2hvIC1lICIke1JFRH3plJnor6/vvJpOUE0g5YmN57yA6K6+572u5aSx6LSlJHtOQ30iCiAgICAgICAgZXhpdCAxCiAgICBmaQogICAgZ3JlcCAtcXhGICJleHBvcnQgUEFUSD0kTlBNX0JJTjokUEFUSCIgIiRCQVNIUkMiIHx8IGVjaG8gImV4cG9ydCBQQVRIPSROUE1fQklOOiRQQVRIIiA+PiAiJEJBU0hSQyIKICAgIGV4cG9ydCBQQVRIPSIkTlBNX0JJTjokUEFUSCIKCiAgICAjIOWcqOWuieijheWJjeWIm+W7uuW/heimgeeahOebruW9le+8iFRlcm11eCDlhbzlrrnmgKflpITnkIbvvIkKICAgIGxvZyAi5Yib5bu6IFRlcm11eCDlhbzlrrnmgKfnm67lvZUiCiAgICBta2RpciAtcCAiJExPR19ESVIiICIkSE9NRS90bXAiCiAgICBpZiBbICQ/IC1uZSAwIF07IHRoZW4KICAgICAgICBsb2cgIuebruW9leWIm+W7uuWksei0pSIKICAgICAgICBlY2hvIC1lICIke1JFRH3plJnor6/vvJrnm67lvZXliJvlu7rlpLHotKUke05DfSIKICAgICAgICBleGl0IDEKICAgIGZpCgogICAgIyDmo4Dmn6Xlubblronoo4Uv5pu05pawIE9wZW5jbGF3CiAgICBJTlNUQUxMRURfVkVSU0lPTj0iIgogICAgTEFURVNUX1ZFUlNJT049IiIKICAgIE5FRURfVVBEQVRFPTAKCiAgICBsb2cgIuajgOafpSBPcGVuY2xhdyDlronoo4XnirbmgIEiCiAgICBpZiBbIC1mICIkTlBNX0JJTi9vcGVuY2xhdyIgXTsgdGhlbgogICAgICAgIGxvZyAiT3BlbmNsYXcg5bey5a6J6KOF77yM5qOA5p+l54mI5pysIgogICAgICAgIGVjaG8gLWUgIiR7QkxVRX3mo4Dmn6UgT3BlbmNsYXcg54mI5pysLi4uJHtOQ30iCiAgICAgICAgSU5TVEFMTEVEX1ZFUlNJT049JChucG0gbGlzdCAtZyBvcGVuY2xhdyAtLWRlcHRoPTAgMj4vZGV2L251bGwgfCBncmVwIC1vRSAnb3BlbmNsYXdAWzAtOV0rXFwuWzAtOV0rXFwuWzAtOV0rJyB8IGN1dCAtZEAgLWYyKQogICAgICAgIGlmIFsgLXogIiRJTlNUQUxMRURfVkVSU0lPTiIgXTsgdGhlbgogICAgICAgICAgICBsb2cgIueJiOacrOaPkOWPluWksei0pe+8jOWwneivleWkh+eUqOaWueazlSIKICAgICAgICAgICAgSU5TVEFMTEVEX1ZFUlNJT049JChucG0gdmlldyBvcGVuY2xhdyB2ZXJzaW9uIDI+L2Rldi9udWxsIHx8IGVjaG8gInVua25vd24iKQogICAgICAgIGZpCiAgICAgICAgZWNobyAtZSAiJHtCTFVFfeW9k+WJjeeJiOacrDogJElOU1RBTExFRF9WRVJTSU9OJHtOQ30iCgogICAgICAgICMg6I635Y+W5pyA5paw54mI5pysCiAgICAgICAgbG9nICLojrflj5bmnIDmlrDniYjmnKzkv6Hmga8iCiAgICAgICAgZWNobyAtZSAiJHtCTFVFfeato+WcqOS7jiBucG0g6I635Y+W5pyA5paw54mI5pys5L+h5oGvLi4uJHtOQ30iCiAgICAgICAgTEFURVNUX1ZFUlNJT049JChucG0gdmlldyBvcGVuY2xhdyB2ZXJzaW9uIDI+L2Rldi9udWxsIHx8IGVjaG8gIiIpCgogICAgICAgIGlmIFsgLXogIiRMQVRFU1RfVkVSU0lPTiIgXTsgdGhlbgogICAgICAgICAgICBsb2cgIuaXoOazleiOt+WPluacgOaWsOeJiOacrOS/oeaBryIKICAgICAgICAgICAgZWNobyAtZSAiJHtZRUxMT1d94pqg77iPICDml6Dms5Xojrflj5bmnIDmlrDniYjmnKzkv6Hmga8vvIjlj6/og73mmK/nvZHnu5zpl67popjvvInvvIzkv53mjIHlvZPliY3niYjmnKwke05DfSIKICAgICAgICBlbHNlCiAgICAgICAgICAgIGVjaG8gLWUgIiR7QkxVRX3mnIDmlrDniYjmnKw6ICRMQVRFU1RfVkVSU0lPTiR7TkN9IgoKICAgICAgICAgICAgIyDnroDljZXniYjmnKzmr5TovoMKICAgICAgICAgICAgaWYgWyAiJElOU1RBTExFRF9WRVJTSU9OIiAhPSAiJExBVEVTVF9WRVJTSU9OIiBdOyB0aGVuCiAgICAgICAgICAgICAgICBsb2cgIuWPkeeOsOaWsOeJiOacrDogJExBVEVTVF9WRVJTSU9OICjlvZPliY06ICRJTlNUQUxMRURfVkVSU0lPTikiCiAgICAgICAgICAgICAgICBlY2hvIC1lICIke1lFTExPV33wn5SUIOWPkeeOsOaWsOeJiOacrDogJExBVEVTVF9WRVJTSU9OICjlvZPliY06ICRJTlNUQUxMRURfVkVSU0lPTikke05DfSIKCiAgICAgICAgICAgICAgICBpZiBbICRGT1JDRV9VUERBVEUgLWVxIDEgXTsgdGhlbgogICAgICAgICAgICAgICAgICAgIGxvZyAi5by65Yi25pu05paw5qih5byP77yM55u05o6l5pu05pawIgogICAgICAgICAgICAgICAgICAgIGVjaG8gLWUgIiR7WUVMTE9Xfeato+WcqOabtOaWsCBPcGVuY2xhdy4uLiR7TkN9IgogICAgICAgICAgICAgICAgICAgIHJ1bl9jbWQgZW52IE5PREVfTExBTUFfQ1BQX1NLSVBfRE9XTkxPQUQ9dHJ1ZSBucG0gaSAtZyBvcGVuY2xhdwogICAgICAgICAgICAgICAgICAgIGlmIFsgJD8gLW5lIDAgXTsgdGhlbgogICAgICAgICAgICAgICAgICAgICAgICBsb2cgIk9wZW5jbGF3IOabtOaWsOWksei0pSIKICAgICAgICAgICAgICAgICAgICAgICAgZWNobyAtZSAiJHtSRUR96ZSZ6K+v77yaT3BlbmNsYXcg5pu05paw5aSx6LSlJHtOQ30iCiAgICAgICAgICAgICAgICAgICAgICAgIGV4aXQgMQogICAgICAgICAgICAgICAgICAgIGZpCiAgICAgICAgICAgICAgICAgICAgbG9nICJPcGVuY2xhdyDmm7TmlrDlrozmiJAiCiAgICAgICAgICAgICAgICAgICAgZWNobyAtZSAiJHtHUkVFTn3inIUgT3BlbmNsYXcg5bey5pu05paw5YiwICRMQVRFU1RfVkVSU0lPTiR7TkN9IgogICAgICAgICAgICAgICAgZWxzZQogICAgICAgICAgICAgICAgICAgIHJlYWQgLXAgIuaYr+WQpuabtOaWsOWIsOaWsOeJiOacrD8gKHkvbikgW+m7mOiupDogeV06ICIgVVBEQVRFX0NIT0lDRQogICAgICAgICAgICAgICAgICAgIFVQREFURV9DSE9JQ0U9JHtVUERBVEVfQ0hPSUNFOi15fQoKICAgICAgICAgICAgICAgICAgICBpZiBbICIkVVBEQVRFX0NIT0lDRSIgPSAieSIgXSB8fCBbICIkVVBEQVRFX0NIT0lDRSIgPSAiWSIgXTsgdGhlbgogICAgICAgICAgICAgICAgICAgICAgICBsb2cgIuW8gOWni+abtOaWsCBPcGVuY2xhdyIKICAgICAgICAgICAgICAgICAgICAgICAgZWNobyAtZSAiJHtZRUxMT1d95q2j5Zyo5pu05pawIE9wZW5jbGF3Li4uJHtOQ30iCiAgICAgICAgICAgICAgICAgICAgICAgIHJ1bl9jbWQgZW52IE5PREVfTExBTUFfQ1BQX1NLSVBfRE9XTkxPQUQ9dHJ1ZSBucG0gaSAtZyBvcGVuY2xhdwogICAgICAgICAgICAgICAgICAgICAgICBpZiBbICQ/IC1uZSAwIF07IHRoZW4KICAgICAgICAgICAgICAgICAgICAgICAgICAgIGxvZyAiT3BlbmNsYXcg5pu05paw5aSx6LSlIgogICAgICAgICAgICAgICAgICAgICAgICAgICAgZWNobyAtZSAiJHtSRUR96ZSZ6K+v77yaT3BlbmNsYXcg5pu05paw5aSx6LSlJHtOQ30iCiAgICAgICAgICAgICAgICAgICAgICAgICAgICBleGl0IDEKICAgICAgICAgICAgICAgICAgICAgICAgZmkKICAgICAgICAgICAgICAgICAgICAgICAgbG9nICJPcGVuY2xhdyDmm7TmlrDlrozmiJAiCiAgICAgIC208   echo -e "${GREEN}脚本执行完成！${NC}，token为：$TOKEN  。常用命令：执行 oclog 查看运行状态； ockill 停止服务；ocr 重启服务。"
log "脚本执行完成"
EOF
";

        final String bootstrapCmd = "if ! command -v openclaw > /dev/null 2>&1; then " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "echo 'Initializing OpenClaw setup environment...' ; " +
            "MIRROR_USTC='https://mirrors.ustc.edu.cn/termux/apt/termux-main'; " +
            "MIRROR_NJU='https://mirrors.nju.edu.cn/termux/apt/termux-main'; " +
            "if curl -s --head --request GET $MIRROR_USTC | grep '200 OK' > /dev/null; then SELECTED=$MIRROR_USTC; else SELECTED=$MIRROR_NJU; fi; " +
            "sed -i \"s|packages.termux.dev/apt/termux-main|$SELECTED|g\" /data/data/com.termux/files/usr/etc/apt/sources.list ; " +
            "pkg update -y && pkg upgrade -y -o Dpkg::Options::=\"--force-confdef\" -o Dpkg::Options::=\"--force-confold\" ; " +
            "pkg install nodejs-lts git tmux openssh termux-api -y ; " +
            "echo '" + scriptBase64 + "' | base64 -d > $HOME/install-openclaw.sh ; " +
            "bash $HOME/install-openclaw.sh ; " +
            "fi\n";

        final TerminalSession terminalSession = newTermuxSession.getTerminalSession();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                terminalSession.write(bootstrapCmd);
            }
        }, 1000); // 延迟 1 秒等待 Shell 就绪

        return newTermuxSession;
    }

    /** Remove a TermuxSession. */
    public synchronized int removeTermuxSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);

        if (index >= 0)
            mShellManager.mTermuxSessions.get(index).finish();

        return index;
    }

    /** Callback received when a {@link TermuxSession} finishes. */
    @Override
    public void onTermuxSessionExited(final TermuxSession termuxSession) {
        if (termuxSession != null) {
            ExecutionCommand executionCommand = termuxSession.getExecutionCommand();

            Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

            mShellManager.mTermuxSessions.remove(termuxSession);

            // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
        }

        updateNotification();
    }





    private ShellCreateMode processShellCreateMode(@NonNull ExecutionCommand executionCommand) {
        if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode))
            return ShellCreateMode.ALWAYS; // Default
        else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode))
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                TermuxPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                    getString(R.string.error_termux_service_execution_command_shell_name_unset, executionCommand.shellCreateMode));
                return null;
            } else {
               return ShellCreateMode.NO_SHELL_WITH_NAME;
            }
        else {
            TermuxPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                getString(R.string.error_termux_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode));
            return null;
        }
    }

    /** Process session action for new session. */
    private void handleSessionAction(int sessionAction, TerminalSession newTerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"");

        switch (sessionAction) {
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.");
                handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession);
                break;
        }
    }

    /** Launch the {@link }TermuxActivity} to bring it to foreground. */
    private void startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this);
        } else {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
            if (preferences == null) return;
            if (preferences.arePluginErrorNotificationsEnabled(false))
                Logger.showToast(this, this.getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal), true);
        }
    }





    /** If {@link TermuxActivity} has not bound to the {@link TermuxService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mTermuxTerminalSessionServiceClient}. Once {@link TermuxActivity} bind
     * callback is received, it should call {@link #setTermuxTerminalSessionClient} to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} so that further terminal sessions are directly
     * passed the {@link TermuxTerminalSessionActivityClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link TermuxTerminalSessionActivityClient} if {@link TermuxActivity} has bound with
     * {@link TermuxService}, otherwise {@link TermuxTerminalSessionServiceClient}.
     */
    public synchronized TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {
        if (mTermuxTerminalSessionActivityClient != null)
            return mTermuxTerminalSessionActivityClient;
        else
            return mTermuxTerminalSessionServiceClient;
    }

    /** This should be called when {@link TermuxActivity#onServiceConnected} is called to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link TermuxTerminalSessionServiceClient}
     * earlier.
     *
     * @param termuxTerminalSessionActivityClient The {@link TermuxTerminalSessionActivityClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    public synchronized void setTermuxTerminalSessionClient(TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)
            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    /** This should be called when {@link TermuxActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link TermuxService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    public synchronized void unsetTermuxTerminalSessionClient() {
        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)
            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionServiceClient);

        mTermuxTerminalSessionActivityClient = null;
    }





    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = TermuxActivity.newInstance(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);


        // Set notification text
        int sessionCount = getTermuxSessionsSize();
        int taskCount = mShellManager.mTermuxTasks.size();
        String notificationText = sessionCount + " session" + (sessionCount == 1 ? "" : "s");
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (taskCount == 1 ? "" : "s");
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) notificationText += " (wake lock held)";


        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        int priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;


        // Build the notification
        Notification.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority,
            TermuxConstants.TERMUX_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        // TermuxSessions are always ongoing
        builder.setOngoing(true);


        // Set Exit button action
        Intent exitIntent = new Intent(this, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, 0));


        // Set Wakelock button actions
        String newWakeAction = wakeLockHeld ? TERMUX_SERVICE.ACTION_WAKE_UNLOCK : TERMUX_SERVICE.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, TermuxService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));


        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    private synchronized void updateNotification() {
        if (mWakeLock == null && mShellManager.mTermuxSessions.isEmpty() && mShellManager.mTermuxTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
        }
    }





    private void setCurrentStoredTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return;
        // Make the newly created session the current one to be displayed
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        if (preferences == null) return;
        preferences.setCurrentSession(terminalSession.mHandle);
    }

    public synchronized boolean isTermuxSessionsEmpty() {
        return mShellManager.mTermuxSessions.isEmpty();
    }

    public synchronized int getTermuxSessionsSize() {
        return mShellManager.mTermuxSessions.size();
    }

    public synchronized List<TermuxSession> getTermuxSessions() {
        return mShellManager.mTermuxSessions;
    }

    @Nullable
    public synchronized TermuxSession getTermuxSession(int index) {
        if (index >= 0 && index < mShellManager.mTermuxSessions.size())
            return mShellManager.mTermuxSessions.get(index);
        else
            return null;
    }

    @Nullable
    public synchronized TermuxSession getTermuxSessionForTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return null;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++) {
            if (mShellManager.mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return mShellManager.mTermuxSessions.get(i);
        }

        return null;
    }

    public synchronized TermuxSession getLastTermuxSession() {
        return mShellManager.mTermuxSessions.isEmpty() ? null : mShellManager.mTermuxSessions.get(mShellManager.mTermuxSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        if (terminalSession == null) return -1;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++) {
            if (mShellManager.mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mShellManager.mTermuxSessions.size(); i < len; i++) {
            terminalSession = mShellManager.mTermuxSessions.get(i).getTerminalSession();
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }

    public synchronized AppShell getTermuxTaskForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        AppShell appShell;
        for (int i = 0, len = mShellManager.mTermuxTasks.size(); i < len; i++) {
            appShell = mShellManager.mTermuxTasks.get(i);
            String shellName = appShell.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return appShell;
        }
        return null;
    }

    public synchronized TermuxSession getTermuxSessionForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        TermuxSession termuxSession;
        for (int i = 0, len = mShellManager.mTermuxSessions.size(); i < len; i++) {
            termuxSession = mShellManager.mTermuxSessions.get(i);
            String shellName = termuxSession.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return termuxSession;
        }
        return null;
    }



    public boolean wantsToStop() {
        return mWantsToStop;
    }

}