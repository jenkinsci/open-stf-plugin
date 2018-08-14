package hudson.plugins.openstf.util;

import com.sun.jersey.api.client.ClientHandlerException;
import hudson.EnvVars;
import hudson.Util;
import hudson.model.Computer;
import hudson.plugins.openstf.Constants;
import hudson.plugins.openstf.Messages;
import hudson.plugins.openstf.exception.ApiFailedException;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.Configuration;
import io.swagger.client.api.DevicesApi;
import io.swagger.client.api.UserApi;
import io.swagger.client.model.AddUserDevicePayload;
import io.swagger.client.model.DeviceListResponseDevices;
import net.sf.json.JSONObject;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Utils {


  /**
   * Convert DeviceListResponseDevices class instance to Map<String, String> for serializability on Jenkins.
   * This used for STFReservedDeviceAction.
   * @param device DeviceListResponseDevices class instance.
   * @return device information converted as Map<String, String> instalce.
   */
  public static Map<String, String> convertDeviceToMap(DeviceListResponseDevices device) {
    Map<String, String> convertedDevice = new HashMap<String, String>();
    convertedDevice.put("image", device.image);
    convertedDevice.put("name", device.name);
    convertedDevice.put("version", device.version);
    convertedDevice.put("sdk", device.sdk);
    return convertedDevice;
  }

  /**
   * Expands the variable in the given string to its value in the variables available to this build.
   * The Jenkins-specific build variables take precedence over environment variables.
   *
   * @param envVars  Map of the environment variables.
   * @param buildVars  Map of the build-specific variables.
   * @param filter  The json token set which may or may not contain variables in the format
     <tt>${foo}</tt>.
   * @return  The given json, with applicable variable expansions done.
   */
  public static Map<String, String> expandVariables(EnvVars envVars, Map<String, String> buildVars,
        Map<String, String> filter) {

    Map<String, String> expandedFilter = new HashMap<String, String>();
    final Map<String, String> vars = new HashMap<String, String>(envVars);
    if (buildVars != null) {
      // Build-specific variables, if any, take priority over environment variables
      vars.putAll(buildVars);
    }

    for (Iterator<String> fi = filter.keySet().iterator(); fi.hasNext(); ) {
      String key = fi.next();
      String value = filter.get(key).toString();

      String result = Util.fixEmptyAndTrim(value);
      if (result != null) {
        result = Util.replaceMacro(result, vars);
      }
      expandedFilter.put(key, result);
    }
    return expandedFilter;
  }

  /**
   * Gets default charset on node.
   * This method try to get the charset by using Jenkins method first.
   * If that fails, get the JVM Default Charset.
   * @return detected charset.
   */
  public static Charset getDefaultCharset() {
    try {
      return Computer.currentComputer().getDefaultCharset();
    } catch (NullPointerException ex) {
      return Charset.defaultCharset();
    }
  }

  /**
   * Gets Attribute names of the STF device.
   * @return Attribute names of the STF device as ListBoxModel.
   */
  public static ListBoxModel getSTFDeviceAttributeListBoxItems() {
    ListBoxModel items = new ListBoxModel();
    for (String value: getSTFDeviceAttributeSet()) {
      items.add(value);
    }

    return items;
  }

  /**
   * Gets Attribute values of the STF device.
   * @param attribute  Attribute name you want to get the values.
   * @return Attribute values of the STF device as ComboBoxModel.
   */
  public static ComboBoxModel getSTFDeviceAttributeValueComboBoxItems(String attribute) {
    ComboBoxModel items = new ComboBoxModel();
    items.add("any");
    for (String value: getSTFDeviceAttributeValueSet(attribute)) {
      items.add(value);
    }

    return items;
  }

  /**
   * Gets Attribute values of the STF device.
   * @param attribute  Attribute name you want to get the values.
   * @return Attribute values of the STF device as ListBoxModel.
   */
  public static ListBoxModel getSTFDeviceAttributeValueListBoxItems(String attribute) {
    ListBoxModel items = new ListBoxModel();
    for (String value: getSTFDeviceAttributeValueSet(attribute)) {
      items.add(value);
    }

    return items;
  }

  /**
   * Set the STF API client up by using given information.
   * @param stfApiEndpoint  stfApiEndpoint The STF API endpoint URL.
   * @param stfToken  stfToken The STF access token.
   */
  public static void setupSTFApiClient(String stfApiEndpoint, boolean ignoreCertError,
      String stfToken) {

    ApiClient stfApiClient = new ApiClient();
    if (ignoreCertError) {
      stfApiClient.setIgnoreCertError(true);
    }
    stfApiClient.setBasePath(stfApiEndpoint);
    stfApiClient.setApiKeyPrefix("Bearer");
    stfApiClient.setApiKey(stfToken);
    Configuration.setDefaultApiClient(stfApiClient);
  }

  public static List<DeviceListResponseDevices> getDeviceList() throws ApiFailedException {
    return getDeviceList(null);
  }

  /**
   * Gets a list of devices that match the given filter.
   * @param filter Conditions of the STF device you want to get.
   * @return List of STF devices that meet the filter.
   * @throws hudson.plugins.openstf.exception.ApiFailedException Failed STF API request.
   */
  public static List<DeviceListResponseDevices> getDeviceList(Map<String, String> filter)
      throws ApiFailedException {

    List<DeviceListResponseDevices> deviceList;
    String fields = "serial,name,model,version,sdk,image,present,owner"
        + ",provider,notes,manufacturer,abi,battery";
    DevicesApi stfDevicesApi = new DevicesApi();

    try {
      deviceList = stfDevicesApi.getDevices(fields).getDevices();
    }  catch (ApiException ex) {
      throw new ApiFailedException("GET /devices API failed");
    }

    if (filter != null) {
      for (Iterator<String> fi = filter.keySet().iterator(); fi.hasNext(); ) {
        String key = fi.next();
        String value = filter.get(key).toString();

        if (!value.equals("any")) {
          for (Iterator<DeviceListResponseDevices> di = deviceList.listIterator();
              di.hasNext(); ) {
            DeviceListResponseDevices device = di.next();
            Class klass = device.getClass();
            try {
              Field field = klass.getField(key);

              if (field.get(device) != null) {
                String deviceValue;
                if (key.equals("battery")) {
                  deviceValue = String.valueOf(device.battery.level);
                }
                else if (key.equals("provider")) {
                  deviceValue = device.provider.name;
                } else {
                  deviceValue = field.get(device).toString();
                }
                if (value.matches(Constants.REGEX_ESCAPED_REGEX_VALUE)) {
                  String regex = value.substring(1, value.length() - 1);
                  if (!deviceValue.matches(regex)) {
                    di.remove();
                  }
                } else {
                  if (!value.equals(deviceValue)) {
                    di.remove();
                  }
                }
              } else {
                if (!value.equals("null")) {
                  di.remove();
                }
              }
            } catch (NoSuchFieldException ex) {
              //ignore
            } catch (IllegalAccessException ex) {
              //ignore
            }
          }
        }
      }
    }
    return deviceList;
  }

  /**
   * Gets a STF device that matches the given id.
   * @param deviceId The id of the device you want to get.
   * @return a STF device that matches the given id.
   * @throws hudson.plugins.openstf.exception.ApiFailedException Failed STF API request.
   */
  public static DeviceListResponseDevices getSTFDeviceById(String deviceId)
      throws ApiFailedException {

    DeviceListResponseDevices device = null;

    DevicesApi stfDevicesApi = new DevicesApi();
    String fields = "serial,name,model,version,sdk,image,present,owner"
        + ",remoteConnectUrl,provider,notes,manufacturer,abi,battery";
    try {
      device = stfDevicesApi.getDeviceBySerial(deviceId, fields).getDevice();
    } catch (ApiException ex) {
      throw new ApiFailedException("GET /devices/" + deviceId + " API failed");
    }
    return device;
  }

  /**
   * Reserve a STF device.
   * @param device  The device you want to reserve.
   * @throws hudson.plugins.openstf.exception.ApiFailedException Failed STF API request.
   */
  public static void reserveSTFDevice(DeviceListResponseDevices device)
      throws ApiFailedException {

    UserApi stfUserApi = new UserApi();
    AddUserDevicePayload body = new AddUserDevicePayload();
    body.setSerial(device.serial);

    try {
      stfUserApi.addUserDevice(body);
    } catch (ApiException ex) {
      throw new ApiFailedException("POST /api/v1/user/devices API failed");
    }
  }

  /**
   * Remote connect to a STF device.
   * @param device  The device you want to connect.
   * @throws hudson.plugins.openstf.exception.ApiFailedException Failed STF API request.
   */
  public static void remoteConnectSTFDevice(DeviceListResponseDevices device)
      throws ApiFailedException {

    UserApi stfUserApi = new UserApi();
    try {
      stfUserApi.remoteConnectUserDeviceBySerial(device.serial);
    } catch (ApiException ex) {
      throw new ApiFailedException("POST /user/devices/" + device.serial
          + "/remoteConnect API failed");
    }
  }

  /**
   * Remote disconnect to a STF device.
   * @param device  The device you want to disconnect.
   * @throws hudson.plugins.openstf.exception.ApiFailedException Failed STF API request.
   */
  public static void remoteDisconnectSTFDevice(DeviceListResponseDevices device)
      throws ApiFailedException {

    UserApi stfUserApi = new UserApi();
    try {
      stfUserApi.remoteDisconnectUserDeviceBySerial(device.serial);
    } catch (ApiException ex) {
      throw new ApiFailedException("DELETE /api/v1/user/devices/" + device.serial
          + "/remoteConnect API failed");
    }
  }

  /**
   * Release a STF device.
   * @param device  The device you want to release.
   * @throws hudson.plugins.openstf.exception.ApiFailedException Failed STF API request.
   */
  public static void releaseSTFDevice(DeviceListResponseDevices device) throws ApiFailedException {

    UserApi stfUserApi = new UserApi();
    try {
      stfUserApi.deleteUserDeviceBySerial(device.serial);
    } catch (ApiException ex) {
      throw new ApiFailedException("DELETE /api/v1/user/devices/" + device.serial + " API failed");
    }
  }

  /**
   * Validates whether the given string looks like a valid Regex value.
   * @param value The Regex string, such as: "/REGEX_VALUE/"
   * @return Whether the Regex value looks valid or not.
  **/
  public static boolean validateRegexValue(String value) {
    try {
      String regex = value.substring(1, value.length() - 1);
      Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      return false;
    }
    return true;
  }

  /**
   * Validates whether the given device filter looks like a valid Regex value.
   * @param filter The device condition set.
   * @return Whether the Regex value looks valid or not.
  **/
  public static boolean validateDeviceFilter(Map<String, String> filter) {
    for (Iterator<String> fi = filter.keySet().iterator(); fi.hasNext(); ) {
      String key = fi.next();
      String value = filter.get(key).toString();

      if (value.matches(Constants.REGEX_ESCAPED_REGEX_VALUE)) {
        if (!validateRegexValue(value)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Validates whether the given URL looks like a valid STF API endpoint.
   * @param stfApiEndpoint The URL string to validate.
   * @return Whether the URL looks valid or not.
   */
  public static FormValidation validateSTFApiEndpoint(String stfApiEndpoint,
      boolean ignoreCertError) {

    if (stfApiEndpoint == null || stfApiEndpoint.equals("")) {
      return FormValidation.ok();
    }

    if (!(stfApiEndpoint.startsWith("http://") || stfApiEndpoint.startsWith("https://"))) {
      return FormValidation.error(Messages.MALFORMED_STF_API_ENDPOINT_URL());
    }

    ApiClient stfApiClient = new ApiClient();
    if (ignoreCertError) {
      stfApiClient.setIgnoreCertError(true);
    }
    stfApiClient.setBasePath(stfApiEndpoint);
    stfApiClient.setConnectTimeout(10 * 1000);
    UserApi stfUserApi = new UserApi(stfApiClient);
    try {
      stfUserApi.getUser();
    } catch (ApiException ex) {
      String expectedMessage = "{\"success\":false,\"description\":\"Requires Authentication\"}";
      if (!(ex.getCode() == 401) || !ex.getResponseBody().equals(expectedMessage)) {
        return FormValidation.error(Messages.INVALID_STF_API_ENDPOINT_URL());
      }
    } catch (ClientHandlerException ex) {
      String message = ex.getMessage();
      if (message.startsWith("java.net.UnknownHostException:")) {
        return FormValidation.error(Messages.CANNOT_RESOLVE_HOST());
      } else if (message.startsWith("java.net.SocketTimeoutException:")) {
        return FormValidation.error(Messages.CONNECTION_TIMEOUT());
      } else if (message.startsWith("java.net.ConnectException:")) {
        return FormValidation.error(message.replaceAll("java.net.ConnectException: ", ""));
      } else {
        return FormValidation.error(message);
      }
    }

    return FormValidation.ok();
  }

  /**
   * Validates whether the given token looks like a valid STF access token.
   * @param stfApiEndpoint The STF API Endpoint URL for use in verification.
   * @param stfToken The token string to validate.
   * @return Whether the STF token looks valid or not.
   */
  public static FormValidation validateSTFToken(String stfApiEndpoint, boolean ignoreCertError,
      String stfToken) {

    if (stfApiEndpoint == null || stfApiEndpoint.equals("")) {
      if (!(stfToken == null || stfToken.equals(""))) {
        return FormValidation.error(Messages.STF_API_ENDPOINT_NOT_SET());
      }
    } else {
      if (validateSTFApiEndpoint(stfApiEndpoint, ignoreCertError).kind == FormValidation.Kind.OK) {
        if (stfToken == null || stfToken.equals("")) {
          return FormValidation.error(Messages.STF_TOKEN_REQUIRED());
        }
        if (!verifyToken(stfApiEndpoint, ignoreCertError, stfToken)) {
          return FormValidation.error(Messages.STF_TOKEN_NOT_VALID());
        }
      } else {
        return FormValidation.error(Messages.STF_API_ENDPOINT_NOT_VALID());
      }
    }
    return FormValidation.ok();
  }

  private static TreeSet<String> getSTFDeviceAttributeSet() {
    TreeSet<String> items = new TreeSet<String>();
    String[] excludeAttributes = {"image", "owner", "present", "remoteConnectUrl"};

    Field[] fields = DeviceListResponseDevices.class.getFields();
    for (Field f: fields) {
      if (!Arrays.asList(excludeAttributes).contains(f.getName())) {
        items.add(f.getName());
      }
    }

    return items;
  }

  private static TreeSet<String> getSTFDeviceAttributeValueSet(String attribute) {

    TreeSet<String> items = new TreeSet<String>();

    try {
      for (DeviceListResponseDevices device : getDeviceList()) {
        Class klass = device.getClass();
        try {
          Field field = klass.getField(attribute);

          if (field.get(device) != null) {
            if (attribute.equals("battery")) {
              items.add(String.valueOf(device.battery.level));}
            else if (attribute.equals("provider")) {
              items.add(device.provider.name);
            } else {
              items.add(field.get(device).toString());
            }
          }
        } catch (NoSuchFieldException ex) {
          //ignore
        } catch (IllegalAccessException ex) {
          //ignore
        }
      }
    } catch (ApiFailedException ex) {
      //ignore
    }

    return items;
  }

  private static boolean verifyToken(String stfApiEndpoint, boolean ignoreCertError,
      String stfToken) {

    setupSTFApiClient(stfApiEndpoint, ignoreCertError, stfToken);
    UserApi stfUserApi = new UserApi();
    try {
      stfUserApi.getUser();
      return true;
    } catch (ApiException ex) {
      return false;
    }
  }
}
