package hudson.plugins.openstf;

import hudson.FilePath;
import hudson.Proc;
import hudson.plugins.android_emulator.sdk.Tool;
import hudson.util.NullStream;

import java.io.IOException;
import java.io.OutputStream;

public class DeviceLogger{
    public FilePath logcatFile;
    public OutputStream logcatStream;
    public String logcatArgs;
    public Proc logWriter;

    public DeviceLogger(AndroidRemoteContext remote, String remoteDeviceSerial, FilePath workspace){
        try{
            this.logcatFile = workspace.createTextTempFile("logcat_" + remoteDeviceSerial, ".log", "", false);
        } catch (java.io.IOException|java.lang.InterruptedException ex) {
            this.logcatFile = null;
        }

        try{
            if (this.logcatFile != null){
                this.logcatStream  = this.logcatFile.write();
            } else {this.logcatStream  = null;}
        } catch (java.io.IOException|java.lang.InterruptedException ex) {
            this.logcatStream = null;
        }

        this.logcatArgs = String.format("-s %s logcat -v time", remoteDeviceSerial);

        try{
            if (this.logcatStream != null){
            this.logWriter = remote.getToolProcStarter(Tool.ADB, this.logcatArgs)
                .stdout(this.logcatStream).stderr(new NullStream()).start();
            }
            else{this.logWriter = null;}
        } catch (java.io.IOException|java.lang.InterruptedException ex) {
            this.logWriter = null;
        }
    }

    public FilePath getLogcatFile(){
        return this.logcatFile;
    }

    public OutputStream getLogcatSteam(){
        return this.logcatStream;
    }

    public String getLogcatArgs() {
        return this.logcatArgs;
    }

    public Proc getLogWriter(){
        return this.logWriter;
    }

}