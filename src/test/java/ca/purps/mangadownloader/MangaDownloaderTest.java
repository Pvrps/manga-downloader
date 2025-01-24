package ca.purps.mangadownloader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.downloader.Downloader;
import ca.purps.mangadownloader.downloader.ParallelDownloader;
import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.Series;
import ca.purps.mangadownloader.scraper.BatotoScraper;
import ca.purps.mangadownloader.scraper.MangaScraper;
import ca.purps.mangadownloader.tracker.DownloadTracker;
import ca.purps.mangadownloader.tracker.Tracker;
import okhttp3.OkHttpClient;

public class MangaDownloaderTest {

    private boolean cleanup;
    private boolean mockScraper;
    private boolean mockDownloader;
    private boolean mockTracker;

    private static final String TEMP_PREFIX = MangaDownloaderTest.class.getSimpleName() + "_";
    private static final String PYTHON_VENV = "C:\\Users\\Purps\\ONEDRI~1\\Sync\\development\\tools\\kcc\\.venv";

    private static final String SERIES_URL = "https://bato.to/series/178142";
    private static final String SERIES_NAME = "178142_Legendary_hearts_Transplant_Reincarnation";

    private static final String CHAPTER_URL = "https://bato.to/chapter/3185633";
    private static final String CHAPTER_NAME = "3185633_Ch_1";

    @Parameters({ "cleanup", "mockScraper", "mockDownloader", "mockTracker" })
    @BeforeClass
    public void beforeClass(
            @Optional("false") boolean cleanup,
            @Optional("false") boolean mockScraper,
            @Optional("false") boolean mockDownloader,
            @Optional("false") boolean mockTracker) {
        this.cleanup = cleanup;
        this.mockScraper = mockScraper;
        this.mockDownloader = mockDownloader;
        this.mockTracker = mockTracker;
    }

    @BeforeTest
    public void beforeTest() {
        this.deleteTemp();

    }

    @AfterTest
    public void afterTest() {
        if (cleanup) {
            this.deleteTemp();
        }
    }

    @Test
    void seriesTest() throws Exception {
        Path tempPath = setupTestEnvironment();
        AppConfig config = createAppConfig(tempPath);
        MangaScraper scraper = createScraper(config);
        Downloader downloader = createDownloader(config, tempPath, MangaDownloaderTest.SERIES_NAME);

        try (MangaDownloader mangaDownloader = new MangaDownloader(scraper, downloader)) {
            Path result = mangaDownloader.download(MangaDownloaderTest.SERIES_URL);

            ArgumentCaptor<Series> seriesCaptor = ArgumentCaptor.forClass(Series.class);
            Mockito.verify(downloader).download(seriesCaptor.capture());
            Series capturedSeries = seriesCaptor.getValue();

            assert capturedSeries != null : "Scraped series should not be null.";
            assert !capturedSeries.getChapters().isEmpty() : "Series should have chapters.";

            assert result.equals(tempPath.resolve(MangaDownloaderTest.SERIES_NAME)) : "The returned path should match the series path.";
        }
    }

    @Test
    void chapterTest() throws Exception {
        Path tempPath = setupTestEnvironment();
        AppConfig config = createAppConfig(tempPath);
        MangaScraper scraper = createScraper(config);
        Downloader downloader = createDownloader(config, tempPath, MangaDownloaderTest.CHAPTER_NAME);

        try (MangaDownloader mangaDownloader = new MangaDownloader(scraper, downloader)) {
            Path result = mangaDownloader.download(MangaDownloaderTest.CHAPTER_URL);

            ArgumentCaptor<Chapter> chapterCaptor = ArgumentCaptor.forClass(Chapter.class);
            Mockito.verify(downloader).download(chapterCaptor.capture());
            Chapter capturedChapter = chapterCaptor.getValue();

            assert capturedChapter != null : "Scraped chapter should not be null.";

            assert capturedChapter.getSeries() != null : "Chapter should have a series.";
            assert capturedChapter.getSeries().getChapters().contains(capturedChapter) : "Chapter should be in the series.";

            String expectedFileName = MangaDownloaderTest.CHAPTER_NAME + "." + (config.isConvertToEpub() ? "epub" : "cbz");
            assert result.equals(tempPath
                    .resolve(MangaDownloaderTest.SERIES_NAME)
                    .resolve(MangaDownloaderTest.CHAPTER_NAME)
                    .resolve(expectedFileName)) : "The returned path should match the chapter path.";
        }
    }

    public void deleteTemp() {
        try {
            Files.walkFileTree(Paths.get(System.getProperty("java.io.tmpdir")), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (dir.getFileName().toString().startsWith(MangaDownloaderTest.TEMP_PREFIX)) {
                        MangaDownloaderTest.deleteDirectoryRecursive(dir);
                        System.out.println("Deleted directory: " + dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void deleteDirectoryRecursive(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    MangaDownloaderTest.deleteDirectoryRecursive(entry);
                } else {
                    Files.delete(entry);
                }
            }
        }
        Files.delete(dir);
    }

    private Path setupTestEnvironment() throws IOException {
        Path path = mockDownloader ? Path.of("test_dir") : Files.createTempDirectory(MangaDownloaderTest.TEMP_PREFIX);
        if (!mockDownloader) {
            System.out.println(path);
            path.toFile().deleteOnExit();
        }
        return path;
    }

    private AppConfig createAppConfig(Path tempPath) {
        return AppConfig.builder()
                .downloadPath(tempPath)
                .historyFilePath(tempPath.resolve("history.json"))
                .pythonEnvPath(MangaDownloaderTest.PYTHON_VENV)
                .build();
    }

    private MangaScraper createScraper(AppConfig config) {
        MangaScraper scraper = mockScraper ? Mockito.mock(MangaScraper.class) : Mockito.spy(new BatotoScraper(config, new OkHttpClient()));

        if (mockScraper) {
            Series series = Mockito.mock(Series.class);
            Chapter chapter = Mockito.mock(Chapter.class);

            Mockito.doReturn(series).when(scraper).scrapeSeries(MangaDownloaderTest.SERIES_URL);
            Mockito.doReturn(chapter).when(scraper).scrapeChapter(MangaDownloaderTest.CHAPTER_URL);

            Mockito.doReturn(Collections.singletonList(chapter)).when(series).getChapters();
            Mockito.doReturn(series).when(chapter).getSeries();
        }

        return scraper;
    }

    private Downloader createDownloader(AppConfig config, Path tempPath, String name) throws IOException {
        String chapterFileName = MangaDownloaderTest.CHAPTER_NAME + "." + (config.isConvertToEpub() ? "epub" : "cbz");

        Downloader downloader = mockDownloader ? Mockito.mock(Downloader.class) : Mockito.spy(new ParallelDownloader(config, createTracker(config)));

        if (mockDownloader || mockScraper) {
            Mockito.doReturn(tempPath.resolve(name)).when(downloader).download(Mockito.any(Series.class));
            Mockito.doReturn(tempPath.resolve(MangaDownloaderTest.SERIES_NAME).resolve(name).resolve(chapterFileName))
                    .when(downloader)
                    .download(Mockito.any(Chapter.class));
        }
        return downloader;
    }

    private Tracker createTracker(AppConfig config) {
        Tracker tracker = mockTracker ? Mockito.mock(Tracker.class) : Mockito.spy(new DownloadTracker(config));

        if (mockTracker || mockDownloader) {
            Mockito.doReturn(false).when(tracker).isChapterDownloaded(Mockito.any());
            Mockito.doReturn(false).when(tracker).isSeriesDownloaded(Mockito.any());
        }

        return tracker;
    }

}