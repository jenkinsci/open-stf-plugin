package hudson.plugins.openstf;

import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.plugins.android_emulator.AndroidEmulator;
import hudson.plugins.openstf.exception.ApiFailedException;
import hudson.plugins.openstf.exception.NoDeviceAvailableException;
import hudson.plugins.openstf.exception.WaitDeviceReleaseInterruptedException;
import hudson.plugins.openstf.exception.WaitDeviceReleaseTimeoutException;
import hudson.plugins.openstf.util.Utils;
import hudson.remoting.Callable;
import io.swagger.client.model.DeviceListResponseDevices;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

class STFConfig implements Serializable {

  private static final long serialVersionUID = 1L;

  private String stfApiEndpoint;
  private String stfToken;
  private Boolean useSpecificKey;
  private String adbPublicKey;
  private String adbPrivateKey;
  private String stfDeviceFilterString;
  private int stfDeviceReleaseWaitTime;
  private boolean enableRemoteAccessToAllFilteredDevices;

  public STFConfig(Boolean useSpecificKey, String adbPublicKey, String adbPrivateKey,
      JSONObject stfDeviceFilter, int stfDeviceReleaseWaitTime,
      boolean enableRemoteAccessToAllFilteredDevices) {

    this.useSpecificKey = useSpecificKey;
    this.adbPublicKey = adbPublicKey;
    this.adbPrivateKey = adbPrivateKey;
    this.stfDeviceFilterString = stfDeviceFilter.toString();
    this.stfDeviceReleaseWaitTime = stfDeviceReleaseWaitTime;
    this.enableRemoteAccessToAllFilteredDevices = enableRemoteAccessToAllFilteredDevices;
  }

  public Boolean getUseSpecificKey() {
    return useSpecificKey;
  }

  public List<DeviceListResponseDevices> reserve() throws STFException, InterruptedException {
    JSONObject filter = JSONObject.fromObject(stfDeviceFilterString);
    List<DeviceListResponseDevices> reservedDevice = new ArrayList<DeviceListResponseDevices>();
    filter.put("present", true);

    List<DeviceListResponseDevices> deviceList = Utils.getDeviceList(filter);

    if (deviceList.isEmpty()) {
      throw new NoDeviceAvailableException("No device available");
    }

    filter.put("owner", "null");
    deviceList = Utils.getDeviceList(filter);

    if (deviceList.isEmpty()) {
      if (stfDeviceReleaseWaitTime == 0) {
        throw new NoDeviceAvailableException("No device available");
      } else {
        try {
          for (int i = 0; i < stfDeviceReleaseWaitTime; i++) {
            Thread.sleep(60 * 1000);
            deviceList = Utils.getDeviceList(filter);
            if (!deviceList.isEmpty()) {
              break;
            }
          }
          if (deviceList.isEmpty()) {
            throw new WaitDeviceReleaseTimeoutException("No device was released in time");
          }
        } catch (InterruptedException ex) {
          throw new WaitDeviceReleaseInterruptedException(
              "Interrupted while waiting for a device to be released", ex);
        }
      }
    }

    Collections.shuffle(deviceList);

    for (DeviceListResponseDevices device: deviceList) {
      try {
        Utils.reserveSTFDevice(device);
        reservedDevice.add(device);
        System.out.println("Debug : " + device.serial);
        if (!this.enableRemoteAccessToAllFilteredDevices) {
            break;
        }
      } catch (ApiFailedException ex) {
        //ignore
      }
    }

    if (reservedDevice.size() == 0) {
      throw new ApiFailedException("POST /api/v1/user/devices API failed");
    }

    for (DeviceListResponseDevices device: reservedDevice){
        // Wati for system reflects
        Thread.sleep(5 * 1000);
        Utils.remoteConnectSTFDevice(device);
    }
    return reservedDevice;
  }

  public void release(DeviceListResponseDevices device) throws STFException {
    Utils.remoteDisconnectSTFDevice(device);
    Utils.releaseSTFDevice(device);
  }

  public Callable<Boolean, IOException> getAdbKeySettingTask(BuildListener listener) {
    return new AdbKeySettingTask(listener, adbPublicKey, adbPrivateKey);
  }

  private static final class AdbKeySettingTask extends MasterToSlaveCallable<Boolean, IOException> {

    private static final long serialVersionUID = 1L;

    private final TaskListener listener;
    private transient PrintStream logger;

    private final String adbPublicKey;
    private final String adbPrivateKey;

    public AdbKeySettingTask(BuildListener listener, String adbPublicKey, String adbPrivateKey) {
      this.listener = listener;

      this.adbPublicKey = adbPublicKey;
      this.adbPrivateKey = adbPrivateKey;
    }

    public Boolean call() throws IOException {

      if (logger == null) {
        logger = listener.getLogger();
      }

      if ((adbPublicKey != null) && (adbPrivateKey != null)) {
        File publicKeyFile = new File(System.getProperty("user.home") + File.separator + ".android"
            + File.separator + "adbkey.pub");
        File privateKeyFile = new File(System.getProperty("user.home") + File.separator + ".android"
            + File.separator + "adbkey");
        PrintWriter publicKeyPw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(publicKeyFile), Utils.getDefaultCharset().displayName())));
        PrintWriter privateKeyPw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(privateKeyFile), Utils.getDefaultCharset().displayName())));

        AndroidEmulator.log(logger,
            Messages.OVERWRITE_ADBKEY_FILE(publicKeyFile.getPath()));
        publicKeyPw.print(adbPublicKey);
        AndroidEmulator.log(logger,
            Messages.OVERWRITE_ADBKEY_FILE(privateKeyFile.getPath()));
        privateKeyPw.print(adbPrivateKey);

        publicKeyPw.close();
        privateKeyPw.close();

        return true;
      } else {
        AndroidEmulator.log(logger, Messages.ADBKEY_IS_NOT_SET());
        return false;
      }
    }
  }
}
