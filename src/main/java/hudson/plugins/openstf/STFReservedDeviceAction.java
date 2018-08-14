package hudson.plugins.openstf;

import hudson.model.Action;
import hudson.plugins.openstf.Messages;
import io.swagger.client.model.DeviceListResponseDevices;
import org.kohsuke.stapler.export.Exported;

import java.net.URL;
import java.util.Map;

public class STFReservedDeviceAction implements Action {
  private final String stfApiEndpoint;
  private final Map<String, String> reservedDevice;

  public STFReservedDeviceAction(String stfApiEndpoint, Map<String, String> reservedDevice) {
    this.stfApiEndpoint = stfApiEndpoint;
    this.reservedDevice = reservedDevice;
  }

  /**
   * Get the device image URL.
   * This method is called by Jenkins.
   * return image URL
   */
  @Exported
  public String getDeviceIcon() {
    String path = "/static/app/devices/icon/x120/";
    if (reservedDevice.get("image") != null) {
      path += reservedDevice.get("image");
    } else {
      path += "_default.jpg";
    }
    try {
      URL iconUrl = new URL(new URL(stfApiEndpoint), path);
      return iconUrl.toString();
    } catch (Exception ex) {
      return "";
    }
  }

  @Exported
  public String getSummary() {
    return Messages.PUBLISH_RESERVED_DEVICE_INFO(
        reservedDevice.get("name"),
        reservedDevice.get("sdk"),
        reservedDevice.get("version")
    );
  }

  public String getDisplayName() {
    return null;
  }

  public String getIconFileName() {
    return null;
  }

  public String getUrlName() {
    return null;
  }
}
