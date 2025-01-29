package ca.purps.mangadownloader;

import java.nio.file.Path;

import ca.purps.mangadownloader.downloader.Downloader;
import ca.purps.mangadownloader.model.MangaEntity;
import ca.purps.mangadownloader.scraper.BatotoScraper;
import ca.purps.mangadownloader.scraper.KunMangaScraper;
import ca.purps.mangadownloader.scraper.MangaScraper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MangaDownloader implements AutoCloseable {

    private final MangaScraper scraper;
    private final Downloader downloader;

    public Path download(String url) {
        MangaEntity entity = null;

        if (scraper instanceof BatotoScraper) {
            if (url.contains("/series/")) {
                entity = scraper.scrapeSeries(url);
            } else if (url.contains("/chapter/")) {
                entity = scraper.scrapeChapter(url);
            } else {
                throw new IllegalArgumentException("Invalid URL: " + url);
            }
        } else if (scraper instanceof KunMangaScraper) {
            if (url.contains("/chapter")) {
                entity = scraper.scrapeChapter(url);
            } else if (url.contains("/manga/")) {
                entity = scraper.scrapeSeries(url);
            } else {
                throw new IllegalArgumentException("Invalid URL: " + url);
            }
        }

        Path path = downloader.download(entity);
        if (path != null) {
            System.out.println(path);
        }
        return path;
    }

    @Override
    public void close() throws Exception {
        if (downloader instanceof AutoCloseable) {
            ((AutoCloseable) downloader).close();
        }
    }

}