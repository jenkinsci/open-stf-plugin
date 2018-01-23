package hudson.plugins.openstf;

import static hudson.plugins.android_emulator.AndroidEmulator.log;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Result;
import hudson.plugins.android_emulator.AndroidEmulator;
import hudson.plugins.android_emulator.SdkInstallationException;
import hudson.plugins.android_emulator.SdkInstaller;
import hudson.plugins.android_emulator.sdk.AndroidSdk;
import hudson.plugins.android_emulator.sdk.Tool;
import hudson.plugins.openstf.exception.ApiFailedException;
import hudson.plugins.openstf.util.Utils;
import hudson.remoting.Callable;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ArgumentListBuilder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.NullStream;
import io.swagger.client.model.DeviceListResponseDevices;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

public class STFBuildWrapper extends BuildWrapper {

  /** Timeout value for STF device connection to complete. */
  private static final int STF_DEVICE_CONNECT_COMPLETE_TIMEOUT_MS = 30 * 1000;

  /** Interval during which killing a process should complete. */
  private static final int KILL_PROCESS_TIMEOUT_MS = 10 * 1000;

  private DescriptorImpl descriptor;
  private AndroidEmulator.DescriptorImpl emulatorDescriptor;

  public JSONObject deviceCondition;
  public final int deviceReleaseWaitTime;
  public final boolean enableRemoteAccessToAllFilteredDevices;
  public final List<DeviceLogger> DevicesLoggerArray;

  /**
   * Allocates a STFBuildWrapper object.
   * @param deviceCondition Condition set of the STF device user want to use.
   * @param deviceReleaseWaitTime Waiting-time for the STF device to be released
   */
  @DataBoundConstructor
  public STFBuildWrapper(JSONObject deviceCondition, int deviceReleaseWaitTime, boolean enableRemoteAccessToAllFilteredDevices) {
    this.deviceCondition = deviceCondition;
    this.deviceReleaseWaitTime = deviceReleaseWaitTime;
    this.enableRemoteAccessToAllFilteredDevices = enableRemoteAccessToAllFilteredDevices;
    this.DevicesLoggerArray = new ArrayList<DeviceLogger>();
  }

  @Override
  public Environment setUp(AbstractBuild build, final Launcher launcher, BuildListener listener)
      throws IOException, InterruptedException {

    final PrintStream logger = listener.getLogger();

    Jenkins hudsonInstance = Jenkins.getInstance();

    if (descriptor == null) {
      descriptor = hudsonInstance.getDescriptorByType(DescriptorImpl.class);
    }
    if (emulatorDescriptor == null) {
      emulatorDescriptor = hudsonInstance.getDescriptorByType(AndroidEmulator.DescriptorImpl.class);
    }

    // Substitute environment and build variables into config
    final EnvVars envVars = hudson.plugins.android_emulator.util.Utils
        .getEnvironment(build, listener);
    final Map<String, String> buildVars = build.getBuildVariables();

    String stfApiEndpoint = descriptor.stfApiEndpoint;
    String stfToken = descriptor.stfToken;
    Boolean useSpecificKey = descriptor.useSpecificKey;
    String adbPublicKey = descriptor.adbPublicKey;
    String adbPrivateKey = descriptor.adbPrivateKey;
    JSONObject deviceFilter = Utils.expandVariables(envVars, buildVars, this.deviceCondition);
    boolean ignoreCertError = descriptor.ignoreCertError;

    if (!Utils.validateDeviceFilter(deviceFilter)) {
      log(logger, Messages.INVALID_DEVICE_CONDITION_SET_IS_GIVEN());
      build.setResult(Result.NOT_BUILT);
      return null;
    }

    Utils.setupSTFApiClient(stfApiEndpoint, ignoreCertError, stfToken);

    // SDK location
    String androidHome = hudson.plugins.android_emulator.util.Utils
				.getConfiguredAndroidHome();
    // Validate Setting values
    String configError = isConfigValid(stfApiEndpoint, ignoreCertError, stfToken);
    if (configError != null) {
      log(logger, Messages.ERROR_MISCONFIGURED(configError));
      build.setResult(Result.NOT_BUILT);
      return null;
    }

    // Confirm that the required SDK tools are available
    AndroidSdk androidSdk = hudson.plugins.android_emulator.util.Utils
        .getAndroidSdk(launcher, androidHome, null);
    if (androidSdk == null) {
      if (!emulatorDescriptor.shouldInstallSdk) {
        // Couldn't find an SDK, don't want to install it, give up
        log(logger, hudson.plugins.android_emulator.Messages.SDK_TOOLS_NOT_FOUND());
        build.setResult(Result.NOT_BUILT);
        return null;
      }

      // Ok, let's download and install the SDK
      log(logger, hudson.plugins.android_emulator.Messages.INSTALLING_SDK());
      try {
        androidSdk = SdkInstaller.install(launcher, listener, null);
      } catch (SdkInstallationException ex) {
        log(logger, hudson.plugins.android_emulator.Messages.SDK_INSTALLATION_FAILED(), ex);
        build.setResult(Result.NOT_BUILT);
        return null;
      }
    }

    String displayHome =
        androidSdk.hasKnownRoot()
            ? androidSdk.getSdkRoot() : hudson.plugins.android_emulator.Messages.USING_PATH();
    log(logger, hudson.plugins.android_emulator.Messages.USING_SDK(displayHome));
    STFConfig stfConfig = new STFConfig(useSpecificKey, adbPublicKey, adbPrivateKey,
        deviceFilter, deviceReleaseWaitTime, this.enableRemoteAccessToAllFilteredDevices);

    return doSetup(build, launcher, listener, androidSdk, stfConfig);
  }

  private Environment doSetup(final AbstractBuild<?, ?> build, final Launcher launcher,
        final BuildListener listener, final AndroidSdk androidSdk, final STFConfig stfConfig)
        throws IOException, InterruptedException {

    final PrintStream logger = listener.getLogger();

    final AndroidRemoteContext remote =
        new AndroidRemoteContext(build, launcher, listener, androidSdk);

    try {
      List<DeviceListResponseDevices>  reservedDevices = stfConfig.reserve();
      log(logger, Messages.SHOW_TOTAL_RESERVED_AMOUNT_OF_DEVICES(Integer.toString(reservedDevices.size())));
      for (DeviceListResponseDevices device: reservedDevices){
        DeviceListResponseDevices reservedJobDevice = Utils.getSTFDeviceById(device.serial);
        remote.setDevice(reservedJobDevice);
        log(logger, Messages.SHOW_RESERVED_DEVICE_INFO(reservedJobDevice.name, reservedJobDevice.serial,
            reservedJobDevice.sdk, reservedJobDevice.version));
        build.addAction(new STFReservedDeviceAction(descriptor.stfApiEndpoint, reservedJobDevice));
      }
    } catch (STFException ex) {
          log(logger, ex.getMessage());
          build.setResult(Result.NOT_BUILT);
          if (remote.getDevice().size() != 0) {
            cleanUp(stfConfig, remote);
          }
      return null;
    }

    if (stfConfig.getUseSpecificKey()) {
      try {
        Callable<Boolean, IOException> task = stfConfig.getAdbKeySettingTask(listener);
        launcher.getChannel().call(task);
      } catch (IOException ex) {
        log(logger, Messages.CANNOT_CREATE_ADBKEY_FILE());
        build.setResult(Result.NOT_BUILT);
        cleanUp(stfConfig, remote);
        return null;
      }
    }

    // We manually start the adb-server so that later commands will not have to start it,
    // allowing them to complete faster.
    Proc adbStart =
        remote.getToolProcStarter(Tool.ADB, "start-server").stdout(logger).stderr(logger).start();
    adbStart.joinWithTimeout(5L, TimeUnit.SECONDS, listener);
    Proc adbStart2 =
        remote.getToolProcStarter(Tool.ADB, "start-server").stdout(logger).stderr(logger).start();
    adbStart2.joinWithTimeout(5L, TimeUnit.SECONDS, listener);

    // Start dumping logcat to temporary file
    final File artifactsDir = build.getArtifactsDir();
    final FilePath workspace = build.getWorkspace();
    if (workspace == null) {
      log(logger, Messages.CANNOT_GET_WORKSPACE_ON_THIS_BUILD());
      build.setResult(Result.FAILURE);
      cleanUp(stfConfig, remote);
      return null;
    }

    for (DeviceListResponseDevices device: remote.getDevice()) {
        DevicesLoggerArray.add(new DeviceLogger(remote, device.remoteConnectUrl, workspace));
    }

    // Make sure we're still connected
    for (DeviceListResponseDevices device: remote.getDevice()){
        connect(remote, device.remoteConnectUrl);
    }

    log(logger, Messages.WAITING_FOR_STF_DEVICE_CONNECT_COMPLETION());
    int connectTimeout = STF_DEVICE_CONNECT_COMPLETE_TIMEOUT_MS;
    boolean connectSucceeded = waitForSTFDeviceConnectCompletion(connectTimeout, remote);

    if (!connectSucceeded) {
      log(logger, Messages.CONNECTING_STF_DEVICE_FAILED());
      build.setResult(Result.FAILURE);
      cleanUp(stfConfig, remote);
      return null;
    }

    // Wait for Authentication
    Thread.sleep(5 * 1000);

    return new Environment() {
      @Override
      public void buildEnvVars(Map<String, String> env) {
        for (DeviceListResponseDevices device: remote.getDevice()) {
            env.put("ANDROID_SERIAL", device.remoteConnectUrl);
            env.put("ANDROID_AVD_DEVICE", device.remoteConnectUrl);
            env.put("ANDROID_ADB_SERVER_PORT", Integer.toString(remote.adbServerPort()));
            break;
        }
        for (int i =0 ; i < DevicesLoggerArray.size(); i++) {
            env.put("ANDROID_TMP_LOGCAT_FILE", DevicesLoggerArray.get(i).getLogcatFile().getRemote());
            break;
        }
        if (androidSdk.hasKnownRoot()) {
          env.put("JENKINS_ANDROID_HOME", androidSdk.getSdkRoot());
          env.put("ANDROID_HOME", androidSdk.getSdkRoot());

          // Prepend the commonly-used Android tools to the start of the PATH for this build
          env.put("PATH+SDK_TOOLS", androidSdk.getSdkRoot() + "/tools/");
          env.put("PATH+SDK_PLATFORM_TOOLS", androidSdk.getSdkRoot() + "/platform-tools/");
          // TODO: Export the newest build-tools folder as well, so aapt and friends can be used
        }
      }

      @Override
      public boolean tearDown(AbstractBuild build, BuildListener listener)
          throws IOException, InterruptedException {

        cleanUp(stfConfig, remote, DevicesLoggerArray, artifactsDir);
        return true;
      }
    };
  }

  private static void connect(AndroidRemoteContext remote, String remote_serial)
      throws IOException, InterruptedException {

    ArgumentListBuilder adbConnectCmd = remote
        .getToolCommand(Tool.ADB, "connect " + remote_serial);
    remote.getProcStarter(adbConnectCmd).start()
        .joinWithTimeout(5L, TimeUnit.SECONDS, remote.launcher().getListener());
  }

  private static void disconnect(AndroidRemoteContext remote, String remote_serial)
      throws IOException, InterruptedException {
    final String args = "disconnect " + remote_serial;
    ArgumentListBuilder adbDisconnectCmd = remote.getToolCommand(Tool.ADB, args);
    remote.getProcStarter(adbDisconnectCmd).start()
        .joinWithTimeout(5L, TimeUnit.SECONDS, remote.launcher().getListener());
  }

  private void cleanUp(STFConfig stfConfig, AndroidRemoteContext remote)
    throws IOException, InterruptedException {
    cleanUp(stfConfig, remote, new ArrayList<DeviceLogger>(), null);
  }

  private void cleanUp(STFConfig stfConfig, AndroidRemoteContext remote, List<DeviceLogger> DevicesLoggerArray,
   File artifactsDir)
      throws IOException, InterruptedException {

    // Disconnect STF device from adb

    for (DeviceListResponseDevices device: remote.getDevice()){
        disconnect(remote, device.remoteConnectUrl);
    }

    try {
      for (DeviceListResponseDevices device: remote.getDevice())
      stfConfig.release(device);
    } catch (STFException ex) {
      log(remote.logger(), ex.getMessage());
    }

    // Clean up logging process
    for( int i=0; i < DevicesLoggerArray.size(); i++){

        if (DevicesLoggerArray.get(i).getLogWriter() != null) {
          if (DevicesLoggerArray.get(i).getLogWriter().isAlive()) {
            // This should have stopped when the emulator was,
            // but if not attempt to kill the process manually.
            // First, give it a final chance to finish cleanly.
            Thread.sleep(3 * 1000);
            if (DevicesLoggerArray.get(i).getLogWriter().isAlive()) {
              hudson.plugins.android_emulator.util.Utils
                  .killProcess(DevicesLoggerArray.get(i).getLogWriter(), KILL_PROCESS_TIMEOUT_MS);
            }
          }
              try {
            DevicesLoggerArray.get(i).logcatStream.close();
          } catch (Exception ex) {
            // ignore
          }

          // Archive the logs
          if (DevicesLoggerArray.get(i).getLogcatFile().length() != 0) {
            log(remote.logger(), hudson.plugins.android_emulator.Messages.ARCHIVING_LOG());
            DevicesLoggerArray.get(i).getLogcatFile().copyTo(new FilePath(artifactsDir).child(DevicesLoggerArray.get(i).getLogcatFile().getName()));
          }
          DevicesLoggerArray.get(i).getLogcatFile().delete();
        }
    }

    ArgumentListBuilder adbKillCmd = remote.getToolCommand(Tool.ADB, "kill-server");
    remote.getProcStarter(adbKillCmd).join();

    remote.cleanUp();
  }

  private String isConfigValid(String stfApiEndpoint, boolean ignoreCertError, String stfToken) {

    if (stfApiEndpoint == null || stfApiEndpoint.equals("")) {
      return Messages.API_ENDPOINT_URL_NOT_SET();
    }
    FormValidation result = descriptor.doCheckSTFApiEndpoint(stfApiEndpoint, ignoreCertError);
    if (FormValidation.Kind.ERROR == result.kind) {
      return result.getMessage();
    }
    result = descriptor.doCheckSTFToken(stfApiEndpoint, ignoreCertError, stfToken);
    if (FormValidation.Kind.ERROR == result.kind) {
      return result.getMessage();
    }

    return null;
  }

  private boolean waitForSTFDeviceConnectCompletion(final int timeout,
      AndroidRemoteContext remote) {

    long start = System.currentTimeMillis();
    int sleep = timeout / (int) (Math.sqrt(timeout / (double) 1000) * 2);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ArgumentListBuilder adbDevicesCmd = remote.getToolCommand(Tool.ADB, "devices");
    boolean shownUnauthorizedOnce = false;

    try {
      while (System.currentTimeMillis() < start + timeout) {
        remote.getProcStarter(adbDevicesCmd).stdout(out).stderr(out).start()
            .joinWithTimeout(5L, TimeUnit.SECONDS, remote.launcher().getListener());

        int unauthorized = 0;

        String devicesResult = out.toString(Utils.getDefaultCharset().displayName());
        String lineSeparator =
            Computer.currentComputer().getSystemProperties().get("line.separator").toString();
        for (String line: devicesResult.split(lineSeparator)) {
          if (line != null) {
              for (DeviceListResponseDevices device: remote.getDevice()){

                if (line.contains(device.remoteConnectUrl) && line.contains("device")) {
                  return true;
                }

                if (line.contains(device.remoteConnectUrl) && line.contains("unauthorized")) {
                    unauthorized++;

                    //Show without rising exception, that we can't authorize device
                    if (unauthorized == 4 && !shownUnauthorizedOnce) {
                        log(remote.logger(), Messages.DEVICE_UNAUTHORIZED());
                        shownUnauthorizedOnce=true;
                    }
              }
            }
          }
        }

        Thread.sleep(sleep);
      }
    } catch (InterruptedException ex) {
      log(remote.logger(), Messages.INTERRUPTED_DURING_STF_DEVICE_CONNECT_COMPLETION());
    } catch (IOException ex) {
      log(remote.logger(), Messages.COULD_NOT_CHECK_STF_DEVICE_CONNECT_COMPLETION());
      ex.printStackTrace(remote.logger());
    }
    return false;
  }

  @Extension
  public static final class DescriptorImpl extends BuildWrapperDescriptor {

    public String stfApiEndpoint = "";
    public String stfToken = "";
    public Boolean useSpecificKey = false;
    public String adbPublicKey;
    public String adbPrivateKey;
    public boolean ignoreCertError = false;

    public DescriptorImpl() {
      super(STFBuildWrapper.class);
      load();
    }

    @Override
    public String getDisplayName() {
      return Messages.JOB_DESCRIPTION();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
      stfApiEndpoint = json.optString("stfApiEndpoint");
      stfToken = json.optString("stfToken");
      useSpecificKey = json.optBoolean("useSpecificKey", false);
      if (useSpecificKey) {
        adbPublicKey = Util.fixEmptyAndTrim(json.optString("adbPublicKey"));
        adbPrivateKey = Util.fixEmptyAndTrim(json.optString("adbPrivateKey"));
      } else {
        adbPublicKey = null;
        adbPrivateKey = null;
      }
      ignoreCertError = json.optBoolean("ignoreCertError", false);
      save();
      return true;
    }

    @Override
    public BuildWrapper newInstance(StaplerRequest req, JSONObject formData) throws FormException {
      int deviceReleaseWaitTime = 0;
      Boolean enableRemoteAccessToAllFilteredDevices = false;
      JSONObject deviceCondition = new JSONObject();

      try {
        deviceReleaseWaitTime = Integer.parseInt(formData.getString("deviceReleaseWaitTime"));
        if (deviceReleaseWaitTime < 0) {
          deviceReleaseWaitTime = 0;
        }
      } catch (NumberFormatException ex) {
        // ignore
      } finally {
        formData.discard("deviceReleaseWaitTime");
      }

      try {
        enableRemoteAccessToAllFilteredDevices = Boolean.valueOf(formData.getString("enableRemoteAccessToAllFilteredDevices"));
      } catch (NumberFormatException ex) {
        // ignore
      } finally {
        formData.discard("enableRemoteAccessToAllFilteredDevices");
      }

      JSONArray conditionArray = formData.optJSONArray("condition");
      if (conditionArray != null) {
        for (Object conditionObj: conditionArray) {
          JSONObject condition = JSONObject.fromObject(conditionObj);
          deviceCondition
              .put(condition.getString("conditionName"), condition.getString("conditionValue"));
        }
      } else {
        JSONObject condition = formData.optJSONObject("condition");
        if (condition != null) {
          deviceCondition
              .put(condition.getString("conditionName"), condition.getString("conditionValue"));
        }
      }

      return new STFBuildWrapper(deviceCondition, deviceReleaseWaitTime, enableRemoteAccessToAllFilteredDevices);
    }

    @Override
    public boolean isApplicable(AbstractProject<?, ?> item) {
      return true;
    }

    public ListBoxModel doFillConditionNameItems() {
      Utils.setupSTFApiClient(stfApiEndpoint, ignoreCertError, stfToken);
      return Utils.getSTFDeviceAttributeListBoxItems();
    }

    /**
     * Fill condition value items on Jenkins web view.
     * This method is called by Jenkins.
     * @param conditionName Condition name to get values.
     * @return condition value items.
     */
    public ComboBoxModel doFillConditionValueItems(@QueryParameter String conditionName) {
      if (Util.fixEmpty(stfApiEndpoint) == null || Util.fixEmpty(stfToken) == null) {
        return new ComboBoxModel();
      } else {
        Utils.setupSTFApiClient(stfApiEndpoint, ignoreCertError, stfToken);
        return Utils.getSTFDeviceAttributeValueComboBoxItems(conditionName);
      }
    }

    /**
     * Checking whether the given condition value is valid.
     * This method is called by Jenkins.
     * @return validation result.
     */
    public FormValidation doCheckConditionValue(@QueryParameter String value) {
      if (value.matches(Constants.REGEX_ESCAPED_REGEX_VALUE)) {
        if (!Utils.validateRegexValue(value)) {
          return FormValidation.error(Messages.INVALID_REGEXP_VALUE());
        }
      }
      return FormValidation.ok();
    }

    public FormValidation doCheckSTFApiEndpoint(@QueryParameter String stfApiEndpoint,
        @QueryParameter boolean ignoreCertError) {
      return Utils.validateSTFApiEndpoint(stfApiEndpoint, ignoreCertError);
    }

    public FormValidation doCheckSTFToken(@QueryParameter String stfApiEndpoint,
        @QueryParameter boolean ignoreCertError, @QueryParameter String stfToken) {
      return Utils.validateSTFToken(stfApiEndpoint, ignoreCertError, stfToken);
    }

    /**
     * Display a warning message if 'useSpecificKey' option is selected.
     * This method is called by Jenkins.
     * @return validation result.
     */
    public FormValidation doCheckUseSpecificKey(@QueryParameter Boolean value) {
      if (value) {
        return FormValidation.warning(Messages.ADBKEY_FILE_WILL_BE_OVERWRITTEN());
      } else {
        return FormValidation.ok();
      }
    }

    /**
     * Gets a list of devices that match the given filter, as JSON Array.
     * This method called by javascript in jelly.
     * @param filter Conditions of the STF device you want to get.
     * @return List of STF devices that meet the filter.
     */
    @JavaScriptMethod
    public JSONArray getDeviceListJSON(JSONObject filter) {

      if (Util.fixEmpty(stfApiEndpoint) == null || Util.fixEmpty(stfToken) == null) {
        return new JSONArray();
      }

      if (!Utils.validateDeviceFilter(filter)) {
        return new JSONArray();
      }

      Utils.setupSTFApiClient(stfApiEndpoint, ignoreCertError, stfToken);

      try {
        List<DeviceListResponseDevices> deviceList = Utils.getDeviceList(filter);
        return JSONArray.fromObject(deviceList);
      } catch (ApiFailedException ex) {
        return new JSONArray();
      }
    }

    @JavaScriptMethod
    public synchronized String getStfApiEndpoint() {
      return String.valueOf(stfApiEndpoint);
    }
  }
}
