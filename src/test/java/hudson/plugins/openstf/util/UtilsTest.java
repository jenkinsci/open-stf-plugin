package hudson.plugins.openstf.util;

import org.apache.commons.io.IOUtils;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.junit.*;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import net.sf.json.JSONObject;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.util.ComboBoxModel;


import hudson.EnvVars;

import io.swagger.client.model.DeviceListResponseDevices;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

public class UtilsTest {

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(8888);

  private static final String DUMMY_TOKEN = "dummyToken";

  @Before
  public void init() throws Exception {

    InputStream authErrorStream = Thread.currentThread().getContextClassLoader()
      .getResource("stf/response_models/auth_error.json").openStream();
    stubFor(get(urlEqualTo("/api/v1/user"))
      .willReturn(aResponse()
        .withStatus(401)
        .withBody(IOUtils.toString(authErrorStream, Charset.defaultCharset()))));

    InputStream getUserStream = Thread.currentThread().getContextClassLoader()
      .getResource("stf/response_models/get_user.json").openStream();
    stubFor(get(urlEqualTo("/api/v1/user"))
      .withHeader("Authorization", equalTo("Bearer " + DUMMY_TOKEN))
      .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(IOUtils.toString(getUserStream, Charset.defaultCharset()))));


    InputStream getDevicesStream = Thread.currentThread().getContextClassLoader()
      .getResource("stf/response_models/get_devices.json").openStream();
    stubFor(get(urlPathEqualTo("/api/v1/devices"))
      .withHeader("Authorization", equalTo("Bearer " + DUMMY_TOKEN))
      .withQueryParam("fields", containing(""))
      .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(IOUtils.toString(getDevicesStream, Charset.defaultCharset()))));
  }

  @Test
  public void testExpandVariables() throws Exception {
    EnvVars envVers = new EnvVars();
    envVers.put("MODEL", "HTL22");

    Map<String,String> buildVers = new HashMap<String, String>();
    buildVers.put("VERSION", "4.1.2");

    Map<String, String> filter = new HashMap<String, String>();
    filter.put("model", "$MODEL");
    filter.put("version", "$VERSION");

    Map<String, String> expandedFilter = Utils.expandVariables(envVers, buildVers, filter);
    assertEquals("HTL22", expandedFilter.get("model"));
    assertEquals("4.1.2", expandedFilter.get("version"));
  }

  @Test
  public void testGetSTFDeviceAttributeValueComboBoxItemsForModel() throws Exception {
    setupSTFApiClient();

    ComboBoxModel items = Utils.getSTFDeviceAttributeValueComboBoxItems("model");
    assertEquals(4, items.size());
    assertThat(items, hasItems("any", "HTL22", "SH-04F", "402SH"));
  }

  @Test
  public void testGetSTFDeviceAttributeValueComboBoxItemsForVersion() throws Exception {
    setupSTFApiClient();

    ComboBoxModel items = Utils.getSTFDeviceAttributeValueComboBoxItems("version");
    assertEquals(4, items.size());
    assertThat(items, hasItems("any", "4.1.2", "4.4.2", "4.4.4"));
  }

  @Test
  public void testGetDeviceListWithNoFilter() throws Exception {
    setupSTFApiClient();

    List<DeviceListResponseDevices> deviceList = Utils.getDeviceList();
    assertEquals(5, deviceList.size());
  }

  @Test
  public void testGetDeviceListWithFilter() throws Exception {
    setupSTFApiClient();

    Map<String, String> filter = new HashMap<String, String>();
    filter.put("owner", "null");
    filter.put("present", "true");

    List<DeviceListResponseDevices> deviceList = Utils.getDeviceList(filter);
    assertEquals(1, deviceList.size());
  }

  private void setupSTFApiClient() {
    String dummySTFApiEndpoint = "http://127.0.0.1:8888/api/v1";
    String dummySTFToken = DUMMY_TOKEN;
    Utils.setupSTFApiClient(dummySTFApiEndpoint, false, dummySTFToken);
  }
}
