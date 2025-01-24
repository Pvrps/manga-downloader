package ca.purps.mangadownloader.utility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import ca.purps.mangadownloader.config.AppConfig;
import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ProcessHelper {

    @Value
    public static class ProcessResult {
        private final int exitCode;
        private final String output;
        private final String errorOutput;
    }

    public ProcessResult run(AppConfig config, String command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();

        String venvPath = config.getPythonEnvPath();
        if (!venvPath.isBlank() && command.contains("python")) {
            ProcessHelper.log.info("Using virtual environment: {}", venvPath);

            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                String fullCommand = "cmd.exe /c \"" + venvPath + "\\Scripts\\activate.bat && " + command + "\"";
                processBuilder.command("cmd.exe", "/c", fullCommand);
            } else {
                String fullCommand = "source " + venvPath + "/bin/activate && " + command;
                processBuilder.command("bash", "-c", fullCommand);
            }
        } else {
            processBuilder.command(command.split(" "));
        }

        ProcessHelper.log.debug("Executing Command: {}", String.join(" ", processBuilder.command()));

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }

        return new ProcessResult(exitCode, output.toString(), errorOutput.toString());
    }

}
