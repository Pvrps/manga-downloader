package ca.purps.mangadownloader.config;

import java.nio.file.Path;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class AppConfig {

    @Builder.Default
    private Path downloadPath = Path.of(System.getProperty("user.home"), "manga_downloader");

    @Builder.Default
    private Path historyFilePath = Path.of(System.getProperty("user.home"), "manga_downloader", "history.json");

    @Builder.Default
    private Boolean skipExisting = true;

    @Builder.Default
    private int maxConcurrentDownloads = Runtime.getRuntime().availableProcessors() * 11;

    @Builder.Default
    private int retryAttempts = 3;

    @Builder.Default
    private long retryDelayMs = 1000;

    @Builder.Default
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    @Builder.Default
    private boolean convertToEpub = true;

    @Builder.Default
    private String conversionArguments = "-p KoLC --webtoon --forcecolor --cropping 0 --stretch --upscale --nokepub";

    @Builder.Default
    private String pythonEnvPath = "";

    public static AppConfig defaults() {
        return AppConfig.builder().build();
    }

}