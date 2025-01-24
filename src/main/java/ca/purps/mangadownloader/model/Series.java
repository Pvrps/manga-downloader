package ca.purps.mangadownloader.model;

import java.util.List;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

@Getter
@Builder
@EqualsAndHashCode(exclude = { "chapters" })
public class Series implements MangaEntity {
        @NonNull
        private final String url;
        @NonNull
        private final Integer id;
        @NonNull
        private final String title;
        @NonNull
        private final String description;
        @NonNull
        private final List<String> authors;
        @NonNull
        private final List<String> genres;
        @NonNull
        private final byte[] coverBytes;
        @NonNull
        private final Status status;
        @NonNull
        private final List<Chapter> chapters;
}