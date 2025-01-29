package ca.purps.mangadownloader;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.downloader.ParallelDownloader;
import ca.purps.mangadownloader.scraper.BatotoScraper;
import ca.purps.mangadownloader.scraper.KunMangaScraper;
import ca.purps.mangadownloader.scraper.MangaScraper;
import ca.purps.mangadownloader.tracker.DownloadTracker;
import okhttp3.OkHttpClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "MangaDownloader", mixinStandardHelpOptions = true, description = "Download manga series/chapter from a given URL")
public class Main implements Callable<Path> {

    @Parameters(index = "0", description = "The URL of the manga series/chapter to download")
    private String url;

    @Parameters(index = "1", description = "The destination folder where the manga series/chapter will be saved")
    private Path destination;

    @Option(names = { "--venv" }, description = "Location of the Python virtual environment for KCC conversion")
    private String pythonEnvPath = "";

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }

    @Override
    public Path call() throws Exception {
        AppConfig config = AppConfig.builder()
                .downloadPath(destination)
                .historyFilePath(destination.resolve("history.json"))
                .pythonEnvPath(pythonEnvPath)
                .build();
        OkHttpClient httpClient = new OkHttpClient();

        MangaScraper scraper;
        if (url.startsWith(BatotoScraper.BASE_URL)) {
            scraper = new BatotoScraper(config, httpClient);
        } else if (url.startsWith(KunMangaScraper.BASE_URL)) {
            scraper = new KunMangaScraper(config, httpClient);
        } else {
            throw new IllegalArgumentException("Unsupported URL: " + url);
        }

        try (MangaDownloader app = new MangaDownloader(
                scraper,
                new ParallelDownloader(config, new DownloadTracker(config)))) {

            return app.download(url);
        }
    }

}
