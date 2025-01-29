package ca.purps.mangadownloader.model;

import java.nio.file.Path;
import java.util.List;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
@Builder
@EqualsAndHashCode(exclude = "series")
public class Chapter implements MangaEntity {
    @Builder.Default
    private final int seriesIndex = 1;
    @NonNull
    private final Series series;
    @NonNull
    private final String url;
    @NonNull
    private final String id;
    @NonNull
    private final String name;
    @NonNull
    private final List<String> imageUrls;
    @NonNull
    private final String description;

    @Setter
    private Path archivePath;
}