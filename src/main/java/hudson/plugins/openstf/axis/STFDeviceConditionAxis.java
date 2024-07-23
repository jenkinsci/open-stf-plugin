package hudson.plugins.openstf.axis;

import hudson.Extension;
import hudson.matrix.Axis;
import hudson.matrix.AxisDescriptor;
import hudson.plugins.openstf.STFBuildWrapper;
import hudson.plugins.openstf.util.Utils;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.List;

@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "Intentional")
public class STFDeviceConditionAxis extends Axis {

  public String type;

  @DataBoundConstructor
  public STFDeviceConditionAxis(String name, String type, List<String> values) {
    super(name, values);
    this.type = type;
  }

  @Extension
  public static class DescriptorImpl extends AxisDescriptor {

    public DescriptorImpl() {
      super(STFDeviceConditionAxis.class);
      load();
    }

    @Override
    public Axis newInstance(StaplerRequest req, JSONObject formData) throws FormException {
      String name = formData.getString("name");
      String type = formData.getString("type");
      List<String> values = JSONArray.toList(formData.getJSONArray("values"), String.class);
      return new STFDeviceConditionAxis(name, type, values);
    }

    @Override
    public String getDisplayName() {
      return "STF Device Condition";
    }

    /**
     * Set device condition type filled in jelly.
     * This method is called by Jenkins.
     * @return List of Device condition types.
     */
    public ListBoxModel doFillTypeItems() {

      Jenkins hudsonInstance = Jenkins.getInstance();

      STFBuildWrapper.DescriptorImpl descriptor = hudsonInstance
          .getDescriptorByType(STFBuildWrapper.DescriptorImpl.class);
      Utils.setupSTFApiClient(descriptor.stfApiEndpoint, descriptor.ignoreCertError,
          descriptor.stfToken);

      return Utils.getSTFDeviceAttributeListBoxItems();
    }

    /**
     * Set device condition value filled in jelly.
     * This method is called by Jenkins.
     * @return List of Device condition values.
     */
    public ListBoxModel doFillValuesItems(@QueryParameter String type) {

      Jenkins hudsonInstance = Jenkins.getInstance();

      STFBuildWrapper.DescriptorImpl descriptor = hudsonInstance
          .getDescriptorByType(STFBuildWrapper.DescriptorImpl.class);
      Utils.setupSTFApiClient(descriptor.stfApiEndpoint, descriptor.ignoreCertError,
          descriptor.stfToken);

      return Utils.getSTFDeviceAttributeValueListBoxItems(type);
    }
  }
}
