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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.downloader.Downloader;
import ca.purps.mangadownloader.downloader.ParallelDownloader;
import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.Series;
import ca.purps.mangadownloader.scraper.BatotoScraper;
import ca.purps.mangadownloader.scraper.KunMangaScraper;
import ca.purps.mangadownloader.scraper.MangaScraper;
import ca.purps.mangadownloader.tracker.DownloadTracker;
import ca.purps.mangadownloader.tracker.Tracker;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import okhttp3.OkHttpClient;

public class MangaDownloaderTest {

    private boolean cleanup;

    private boolean mockScraper;
    private boolean mockDownloader;
    private boolean mockTracker;

    private static final String TEMP_PREFIX = MangaDownloaderTest.class.getSimpleName() + "_";
    private static final String PYTHON_VENV = "C:\\Users\\Purps\\ONEDRI~1\\Sync\\development\\tools\\kcc\\.venv";

    @Parameters({ "cleanup", "mockScraper", "mockDownloader", "mockTracker" })
    @BeforeClass
    public void beforeClass(
            @Optional("false") boolean cleanup,
            @Optional("false") boolean mockScraper,
            @Optional("false") boolean mockDownloader,
            @Optional("false") boolean mockTracker) throws ClassNotFoundException {
        this.cleanup = cleanup;
        this.mockScraper = mockScraper;
        this.mockDownloader = mockDownloader;
        this.mockTracker = mockTracker;
    }

    @BeforeTest
    public void beforeTest() {
        Mockito.clearAllCaches();
        this.deleteTemp();
    }

    @AfterTest
    public void afterTest() {
        if (cleanup) {
            this.deleteTemp();
        }
    }

    @DataProvider(name = "seriesScraper")
    public Object[][] seriesScraper() {
        return new Object[][] {
                { TestData
                        .builder()
                        .scraperClass(BatotoScraper.class)
                        .url("https://bato.to/series/178142")
                        .seriesName("178142_Legendary_hearts_Transplant_Reincarnation")
                        .build()
                },
                { TestData
                        .builder()
                        .scraperClass(KunMangaScraper.class)
                        .url("https://kunmanga.com/manga/ill-marry-your-brother")
                        .seriesName("0_I_ll_Marry_Your_Brother")
                        .build()
                }
        };
    }

    @DataProvider(name = "chapterScraper")
    public Object[][] chapterScraper() {
        return new Object[][] {
                { TestData
                        .builder()
                        .scraperClass(BatotoScraper.class)
                        .url("https://bato.to/chapter/3185633")
                        .seriesName("178142_Legendary_hearts_Transplant_Reincarnation")
                        .chapterName("3185633_Ch_1")
                        .build()
                },
                { TestData
                        .builder()
                        .scraperClass(KunMangaScraper.class)
                        .url("https://kunmanga.com/manga/ill-marry-your-brother/chapter-3/")
                        .seriesName("0_I_ll_Marry_Your_Brother")
                        .chapterName("3_chapter_3")
                        .build()
                }
        };
    }

    @Test(dataProvider = "seriesScraper")
    void seriesTest(TestData data) throws Exception {
        Path tempPath = setupTestEnvironment();
        AppConfig config = createAppConfig(tempPath);
        MangaScraper scraper = createScraper(config, data);
        Downloader downloader = createDownloader(config, tempPath, data);

        try (MangaDownloader mangaDownloader = new MangaDownloader(scraper, downloader)) {
            Path result = mangaDownloader.download(data.getUrl());

            ArgumentCaptor<Series> seriesCaptor = ArgumentCaptor.forClass(Series.class);
            Mockito.verify(downloader).download(seriesCaptor.capture());
            Series capturedSeries = seriesCaptor.getValue();

            assert capturedSeries != null : "Scraped series should not be null.";
            assert !capturedSeries.getChapters().isEmpty() : "Series should have chapters.";

            assert result.equals(tempPath.resolve(data.getSeriesName())) : "The returned path should match the series path.";
        }
    }

    @Test(dataProvider = "chapterScraper")
    void chapterTest(TestData data) throws Exception {
        Path tempPath = setupTestEnvironment();
        AppConfig config = createAppConfig(tempPath);
        MangaScraper scraper = createScraper(config, data);
        Downloader downloader = createDownloader(config, tempPath, data);

        try (MangaDownloader mangaDownloader = new MangaDownloader(scraper, downloader)) {
            Path result = mangaDownloader.download(data.getUrl());

            ArgumentCaptor<Chapter> chapterCaptor = ArgumentCaptor.forClass(Chapter.class);
            Mockito.verify(downloader).download(chapterCaptor.capture());
            Chapter capturedChapter = chapterCaptor.getValue();

            assert capturedChapter != null : "Scraped chapter should not be null.";

            assert capturedChapter.getSeries() != null : "Chapter should have a series.";
            assert capturedChapter.getSeries().getChapters().contains(capturedChapter) : "Chapter should be in the series.";

            String expectedFileName = data.getChapterName() + "." + (config.isConvertToEpub() ? "epub" : "cbz");
            assert result.equals(tempPath
                    .resolve(data.getSeriesName())
                    .resolve(data.getChapterName())
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

    private MangaScraper createScraper(AppConfig config, TestData data) {
        MangaScraper scraper;

        if (mockScraper) {
            scraper = data != null
                    ? Mockito.mock(data.getScraperClass())
                    : Mockito.mock(MangaScraper.class);

            Series series = Mockito.mock(Series.class);
            Chapter chapter = Mockito.mock(Chapter.class);

            Mockito.doReturn(series).when(scraper).scrapeSeries(Mockito.anyString());
            Mockito.doReturn(chapter).when(scraper).scrapeChapter(Mockito.anyString());

            Mockito.doReturn(Collections.singletonList(chapter)).when(series).getChapters();
            Mockito.doReturn(series).when(chapter).getSeries();
        } else {
            try {
                if (data != null) {
                    scraper = data.getScraperClass()
                            .getConstructor(AppConfig.class, OkHttpClient.class)
                            .newInstance(config, new OkHttpClient());
                    scraper = Mockito.spy(scraper);
                } else {
                    throw new IllegalStateException("No scraper class specified for non-mock testing");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to create scraper instance", e);
            }
        }

        return scraper;
    }

    private Downloader createDownloader(AppConfig config, Path tempPath, TestData data) throws IOException {
        String chapterFileName = data.getChapterName() + "." + (config.isConvertToEpub() ? "epub" : "cbz");

        Downloader downloader = mockDownloader ? Mockito.mock(Downloader.class) : Mockito.spy(new ParallelDownloader(config, createTracker(config)));

        if (mockDownloader || mockScraper) {
            Mockito.doReturn(tempPath.resolve(data.getSeriesName())).when(downloader).download(Mockito.any(Series.class));

            if (data.getChapterName() != null) {
                Mockito.doReturn(tempPath.resolve(data.getSeriesName()).resolve(data.getChapterName()).resolve(chapterFileName))
                    .when(downloader)
                    .download(Mockito.any(Chapter.class));
            }
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

    @Value
    @Builder
    private static class TestData {
        @NonNull
        private final Class<? extends MangaScraper> scraperClass;
        @NonNull
        private final String url;
        @NonNull
        private final String seriesName;

        private final String chapterName;
    }

}