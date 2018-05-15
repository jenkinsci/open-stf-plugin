// CHECKSTYLE:OFF
package hudson.plugins.openstf;

import hudson.EnvVars;
import hudson.Launcher.ProcStarter;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.plugins.android_emulator.sdk.AndroidSdk;
import hudson.plugins.android_emulator.sdk.Tool;
import hudson.plugins.android_emulator.util.Utils;
import hudson.plugins.android_emulator.sdk.cli.SdkCliCommand;
import hudson.util.ArgumentListBuilder;
import hudson.util.NullStream;
import io.swagger.client.model.DeviceListResponseDevices;
import org.jvnet.hudson.plugins.port_allocator.PortAllocationManager;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.ArrayList;

public class AndroidRemoteContext {
  /** Interval during which an emulator command should complete. */
  public static final int ANDROID_COMMAND_TIMEOUT_MS = 60 * 1000;
  protected static final int PORT_RANGE_START = 5554;
  // Make sure the port is four digits, as there are tools that rely on this
  protected static final int PORT_RANGE_END = 9999;

  private int adbServerPort;
  protected List<String> serial;

  protected PortAllocationManager portAllocator;

  private AndroidSdk sdk;
  private List<DeviceListResponseDevices> stfDevice = null;

  protected AbstractBuild<?, ?> build;
  private BuildListener listener;
  protected Launcher launcher;

  public AndroidRemoteContext(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener,
      AndroidSdk sdk) throws InterruptedException, IOException {

    this.build = build;
    this.listener = listener;
    this.launcher = launcher;
    this.sdk = sdk;
    this.serial = new ArrayList<String>();
    this.stfDevice = new ArrayList<DeviceListResponseDevices>();

    final Computer computer = Computer.currentComputer();

    // Use the Port Allocator plugin to reserve the ports we need
    portAllocator = PortAllocationManager.getManager(computer);
    int[] ports =
        portAllocator.allocatePortRange(this.build, PORT_RANGE_START, PORT_RANGE_END, 1, true);
    adbServerPort = ports[0];
  }

  public void cleanUp() {
    // Free up the TCP ports
    portAllocator.free(adbServerPort);
  }

  public int adbServerPort() {
    return adbServerPort;
  }

  public List<String> getSerial() {
    return serial;
  }

  public BuildListener listener() {
    return listener;
  }

  public Launcher launcher() {
    return launcher;
  }

  public AndroidSdk sdk() {
    return sdk;
  }

  public PrintStream logger() {
    return listener.getLogger();
  }

  /**
   * Sets up a standard {@link ProcStarter} for the current context.
   *
   * @return A ready ProcStarter
   *
   * @throws IOException
   * @throws InterruptedException
   */
  public ProcStarter getProcStarter() throws IOException, InterruptedException {

    final EnvVars buildEnvironment = build.getEnvironment(TaskListener.NULL);
    buildEnvironment.put("ANDROID_ADB_SERVER_PORT", Integer.toString(adbServerPort));
    if (sdk.hasKnownHome()) {
      buildEnvironment.put("ANDROID_SDK_HOME", sdk.getSdkHome());
    }
    if (launcher.isUnix()) {
      buildEnvironment.put("LD_LIBRARY_PATH", String.format("%s/tools/lib", sdk.getSdkRoot()));
    }
    return launcher.launch().stdout(new NullStream()).stderr(logger()).envs(buildEnvironment);
  }

  /**
   * Sets up a standard {@link ProcStarter} for the current adb environment,
   * ready to execute the given command.
   *
   * @param command What command to run
   * @return A ready ProcStarter
   *
   * @throws IOException
   * @throws InterruptedException
   */
  public ProcStarter getProcStarter(ArgumentListBuilder command)
      throws IOException, InterruptedException {
    return getProcStarter().cmds(command);
  }

  /**
   * Generates a ready-to-use ArgumentListBuilder for one of the Android SDK tools,
   * based on the current context.
   *
   * @param tool The Android tool to run.
   * @param args Any extra arguments for the command.
   * @return Arguments including the full path to the SDK and any extra Windows stuff required.
   */
  public ArgumentListBuilder getToolCommand(Tool tool, String args) {
    return Utils.getToolCommand(sdk, launcher.isUnix(), new SdkCliCommand(tool, args));
  }

  /**
   * Generates a ready-to-use ProcStarter for one of the Android SDK tools,
   * based on the current context.
   *
   * @param tool The Android tool to run.
   * @param args Any extra arguments for the command.
   * @return A ready ProcStarter
   * @throws IOException
   * @throws InterruptedException
   */
  public ProcStarter getToolProcStarter(Tool tool, String args)
      throws IOException, InterruptedException {
    return getProcStarter(Utils.getToolCommand(sdk, launcher.isUnix(), new SdkCliCommand( tool, args)));
  }

  public List<DeviceListResponseDevices> getDevice() {
    return stfDevice;
  }

  public void setDevice(DeviceListResponseDevices device) {
    stfDevice.add(device);
    serial.add(device.remoteConnectUrl);
  }
}
