package ca.purps.mangadownloader.downloader;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.converter.EPubConverter;
import ca.purps.mangadownloader.exception.DownloadException;
import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.MangaEntity;
import ca.purps.mangadownloader.model.Series;
import ca.purps.mangadownloader.tracker.Tracker;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public class ParallelDownloader implements Downloader, AutoCloseable {

    private final AppConfig config;
    private final OkHttpClient httpClient;

    private final ExecutorService chapterExecutor;
    private final ExecutorService imageExecutor;

    private final EPubConverter converter;
    private final Tracker tracker;

    public ParallelDownloader(AppConfig config, Tracker tracker) {
        this.config = config;
        this.tracker = tracker;

        this.converter = new EPubConverter(config);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        this.chapterExecutor = Executors.newFixedThreadPool(config.getMaxConcurrentDownloads());
        this.imageExecutor = Executors.newFixedThreadPool(config.getMaxConcurrentDownloads());
    }

    @Override
    public Path download(MangaEntity entity) {
        if (entity instanceof Series series) {
            if (tracker.isSeriesDownloaded(series)) {
                ParallelDownloader.log.info("Series already downloaded: {}", series.getTitle());
                return config.getDownloadPath().resolve(sanitize(series.getTitle()));
            }

            return download(series);
        } else if (entity instanceof Chapter chapter) {
            if (isChapterDownloaded(chapter)) {
                return null;
            }

            Path seriesPath = createDirectory(config.getDownloadPath().resolve(sanitize(chapter.getSeries().getId() + "_" + chapter.getSeries().getTitle())));
            ParallelDownloader.log.debug("Created series directory: {}", seriesPath);

            download(chapter, seriesPath, config.isConvertToEpub(), config.getSkipExisting());

            return chapter.getArchivePath();
        }

        throw new IllegalArgumentException("Unsupported entity type: " + entity.getClass().getSimpleName());
    }

    private Path download(Series series) {
        ParallelDownloader.log.info("Starting download of series: {} to {}", series.getTitle(), config.getDownloadPath());

        Path seriesPath = createDirectory(config.getDownloadPath().resolve(sanitize(series.getId() + "_" + series.getTitle())));
        ParallelDownloader.log.debug("Created series directory: {}", seriesPath);

        List<Chapter> chapters = new ArrayList<>();

        CompletableFuture.allOf(
                series.getChapters()
                        .stream()
                        .map(chapter -> CompletableFuture.supplyAsync(
                                () -> {
                                    if (isChapterDownloaded(chapter)) {
                                        return null;
                                    }

                                    download(chapter, seriesPath, false, false);
                                    tracker.markChapterDownloaded(chapter);
                                    return chapter;
                                },
                                chapterExecutor))
                        .map(future -> future.thenApply(chapter -> {
                            if (chapter != null && chapter.getArchivePath().toString().endsWith(".cbz")) {
                                chapters.add(chapter);
                            }
                            return chapter;
                        }))
                        .toArray(CompletableFuture[]::new))
                .join();

        converter.convertFromCBZ(chapters);

        ParallelDownloader.log.info("Completed downloading series: {}", series.getTitle());
        return seriesPath;
    }

    private void download(Chapter chapter, Path path, boolean shouldConvert, boolean shouldTrack) {
        String sanitizedName = sanitize(chapter.getId() + "_" + chapter.getName());
        ParallelDownloader.log.info("Downloading chapter: {} to {}", sanitizedName, path);

        Path chapterPath = createDirectory(path.resolve(sanitizedName));
        ParallelDownloader.log.debug("Created chapter directory: {}", chapterPath);

        try {
            final AtomicInteger index = new AtomicInteger(0);

            CompletableFuture.allOf(
                    chapter.getImageUrls()
                            .stream()
                            .map(url -> downloadImage(url, chapterPath, index.incrementAndGet()))
                            .toArray(CompletableFuture[]::new))
                    .join();

            Path archivePath = createArchive(chapterPath);
            chapter.setArchivePath(archivePath);
            cleanupImages(chapterPath);

            ParallelDownloader.log.info("Successfully downloaded chapter: {}", chapter.getName());

            if (shouldTrack) {
                tracker.markChapterDownloaded(chapter);
            }

            if (shouldConvert) {
                converter.convertFromCBZ(List.of(chapter));
            }
        } catch (DownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new DownloadException(String.format("Failed to download chapter: %s", chapter.getName()), e);
        }
    }

    private CompletableFuture<Path> downloadImage(String url, Path destination, int index) {
        return CompletableFuture.supplyAsync(() -> {
            String fileExtension = sanitize(url.substring(url.lastIndexOf('.') + 1));
            Path imagePath = destination.resolve(String.format("%03d.%s", index, fileExtension));
            ParallelDownloader.log.debug("Downloading image: {} to {}", url, imagePath);

            for (int attempt = 1; attempt <= config.getRetryAttempts(); attempt++) {
                try {
                    Request request = new Request.Builder()
                            .url(url)
                            .header("User-Agent", config.getUserAgent())
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            ParallelDownloader.log.warn("Failed to download image (attempt {}/{}): {} (Status code: {})",
                                    attempt, config.getRetryAttempts(), url, response.code());
                            throw new IOException("Failed to download image: " + response.code());
                        }

                        Files.copy(response.body().byteStream(), imagePath, StandardCopyOption.REPLACE_EXISTING);
                        ParallelDownloader.log.debug("Successfully downloaded image: {}", url);
                        return imagePath;
                    }
                } catch (Exception e) {
                    if (attempt == config.getRetryAttempts()) {
                        throw new DownloadException(String.format("Failed to download image after %d attempts: %s", attempt, url), e);
                    }
                    ParallelDownloader.log.debug("Retrying download after failure (attempt {}/{}): {}",
                            attempt, config.getRetryAttempts(), url);
                    try {
                        Thread.sleep(config.getRetryDelayMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DownloadException("Download interrupted", ie);
                    }
                }
            }

            throw new DownloadException("Failed to download image: " + url);
        }, imageExecutor);
    }

    private Path createArchive(Path sourceDir) throws IOException {
        Path archivePath = sourceDir.resolve(sourceDir.getFileName() + ".cbz");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            Files.walk(sourceDir)
                    .filter(path -> !Files.isDirectory(path) && !path.toString().endsWith(".cbz"))
                    .forEach(path -> {
                        try {
                            ZipEntry entry = new ZipEntry(sourceDir.relativize(path).toString());
                            zos.putNextEntry(entry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new DownloadException("Failed to add file to archive: " + path, e);
                        }
                    });
        }

        ParallelDownloader.log.debug("Created archive: {}", archivePath);

        return archivePath;
    }

    private void cleanupImages(Path directory) {
        try {
            long[] stats = Files.walk(directory)
                .filter(path -> !Files.isDirectory(path) && !path.toString().endsWith(".cbz"))
                    .map(path -> {
                        try {
                            Files.delete(path);
                            return new long[] { 1, 1 };
                        } catch (IOException e) {
                            ParallelDownloader.log.warn("Failed to delete temporary image file: {}", path, e);
                            return new long[] { 0, 1 };
                        }
                    })
                    .reduce(new long[] { 0, 0 }, (a, b) -> new long[] { a[0] + b[0], a[1] + b[1] });

            long deletedFiles = stats[0];
            long totalFiles = stats[1];

            if (deletedFiles == totalFiles && totalFiles > 0) {
                ParallelDownloader.log.debug("Cleaned up all {} temporary image files", totalFiles);
            } else if (deletedFiles > 0) {
                ParallelDownloader.log.warn("Cleaned up {} out of {} temporary image files", deletedFiles, totalFiles);
            } else {
                ParallelDownloader.log.warn("No temporary image files were cleaned up");
            }
        } catch (IOException e) {
            ParallelDownloader.log.warn("Error walking directory for cleanup", e);
        }
    }

    private Path createDirectory(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new DownloadException("Failed to create directory: " + path, e);
        }
    }

    private String sanitize(String input) {
        String sanitized = input.replaceAll("[^a-zA-Z0-9_-]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^[._-]+|[._-]+$", "");
        return sanitized;
    }

    private boolean isChapterDownloaded(Chapter chapter) {
        boolean downloaded = tracker.isChapterDownloaded(chapter);
        if (downloaded) {
            ParallelDownloader.log.info("Chapter already downloaded: {}", chapter.getName());
        }
        return downloaded;
    }

    @Override
    public void close() {
        chapterExecutor.shutdown();
        imageExecutor.shutdown();
        try {
            if (!chapterExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                chapterExecutor.shutdownNow();
            }
            if (!imageExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                imageExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}