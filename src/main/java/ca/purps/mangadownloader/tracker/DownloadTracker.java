package ca.purps.mangadownloader.tracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.exception.TrackerException;
import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.Series;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;

@RequiredArgsConstructor
public class DownloadTracker implements Tracker {

    private final AppConfig config;

    private final ObjectMapper mapper = new ObjectMapper();

    @Getter(lazy = true)
    private final ObjectNode data = loadOrCreateTracker();

    private ObjectNode loadOrCreateTracker() {
        Path historyFilePath = config.getHistoryFilePath();
        try {
            if (Files.exists(historyFilePath)) {
                return (ObjectNode) mapper.readTree(historyFilePath.toFile());
            }
        } catch (IOException e) {
            throw new TrackerException(String.format("Failed to load %s", historyFilePath), e);
        }

        ObjectNode newData = mapper.createObjectNode();
        newData.set("series", mapper.createObjectNode());
        return newData;
    }

    @Override
    @Synchronized
    public void markChapterDownloaded(Chapter chapter) {
        ObjectNode seriesNode = (ObjectNode) getData().get("series");
        ObjectNode seriesEntry = (ObjectNode) seriesNode.get(chapter.getSeries().getUrl());

        if (seriesEntry == null) {
            seriesEntry = seriesNode.putObject(chapter.getSeries().getUrl());
            seriesEntry.put("title", chapter.getSeries().getTitle());
        }

        ObjectNode chaptersNode = seriesEntry.hasNonNull("chapters")
                ? (ObjectNode) seriesEntry.get("chapters")
                : seriesEntry.putObject("chapters");

        ObjectNode chapterNode = chaptersNode.putObject(chapter.getUrl());
        chapterNode.put("name", chapter.getName());
        chapterNode.put("completed", true);
        chapterNode.put("downloadedAt", Instant.now().toString());

        saveTracker();
    }

    @Override
    @Synchronized
    public boolean isSeriesDownloaded(Series series) {
        if (!config.getSkipExisting()) {
            return false;
        }

        ObjectNode seriesNode = (ObjectNode) getData().get("series");
        ObjectNode seriesEntry = (ObjectNode) seriesNode.get(series.getUrl());

        if (seriesEntry == null || !seriesEntry.hasNonNull("chapters")) {
            return false;
        }

        ObjectNode chaptersNode = (ObjectNode) seriesEntry.get("chapters");

        return series.getChapters()
                .stream()
                .allMatch(chapter -> chaptersNode.has(chapter.getUrl()) &&
                        chaptersNode.get(chapter.getUrl()).get("completed").asBoolean());
    }

    @Override
    @Synchronized
    public boolean isChapterDownloaded(Chapter chapter) {
        if (!config.getSkipExisting()) {
            return false;
        }

        ObjectNode seriesNode = (ObjectNode) getData().get("series");
        ObjectNode seriesEntry = (ObjectNode) seriesNode.get(chapter.getSeries().getUrl());

        if (seriesEntry == null || !seriesEntry.hasNonNull("chapters")) {
            return false;
        }

        ObjectNode chaptersNode = (ObjectNode) seriesEntry.get("chapters");
        ObjectNode chapterNode = (ObjectNode) chaptersNode.get(chapter.getUrl());

        return chapterNode != null && chapterNode.get("completed").asBoolean();
    }

    private void saveTracker() {
        try {
            Path historyFilePath = config.getHistoryFilePath();
            Files.createDirectories(historyFilePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(historyFilePath.toFile(), getData());
        } catch (IOException e) {
            throw new TrackerException("Failed to save download tracker", e);
        }
    }

}
