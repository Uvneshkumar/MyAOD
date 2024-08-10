package uvnesh.myaod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuShell {

    private static final String mDir = "/";
    private static List<String> mOutput;
    private static ShizukuRemoteProcess mProcess = null;
    private static String mCommand;

    public ShizukuShell(List<String> output, String command) {
        mOutput = output;
        mCommand = command;
    }

    public String exec() {
        try {
            mProcess = Shizuku.newProcess(new String[]{"sh", "-c", mCommand}, null, mDir);
            BufferedReader mInput = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
            BufferedReader mError = new BufferedReader(new InputStreamReader(mProcess.getErrorStream()));
            String line;
            while ((line = mInput.readLine()) != null) {
                mOutput.add(line);
            }
            while ((line = mError.readLine()) != null) {
                mOutput.add(line);
            }
            mProcess.waitFor();
        } catch (Exception ignored) {
        }
        if (mOutput.isEmpty()) {
            return "";
        } else {
            return mOutput.get(0);
        }
    }

}