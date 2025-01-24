# Stage 1: Build
FROM maven:3.8-openjdk-17-slim AS build

# Install system dependencies
RUN apt-get update && \
    apt-get install -y git python3 python3-pip python3-venv && \
    apt-get clean

# Create non-root user
RUN useradd -m appuser
USER appuser
RUN mkdir /home/appuser/apps && chown -R appuser:appuser /home/appuser/apps

WORKDIR /home/appuser/apps

# Clone repositories
RUN rm -rf /home/appuser/apps/manga_downloader && \
    git clone https://github.com/PurpleDxD/manga-downloader /home/appuser/apps/manga_downloader && \
    rm -rf /home/appuser/apps/kcc && \
    git clone https://github.com/ciromattia/kcc /home/appuser/apps/kcc

# Setup Python virtual environment
WORKDIR /home/appuser/apps/kcc
RUN python3 -m venv /home/appuser/apps/kcc/venv && \
    /home/appuser/apps/kcc/venv/bin/python -m pip install --upgrade pip && \
    /home/appuser/apps/kcc/venv/bin/pip install -r /home/appuser/apps/kcc/requirements.txt

# Build Java project
WORKDIR /home/appuser/apps/manga_downloader
RUN git pull && mvn clean package -DskipTests

# Stage 2: Runtime
FROM openjdk:17-slim

LABEL org.opencontainers.image.source=https://github.com/PurpleDxD/manga-downloader
LABEL org.opencontainers.image.description="Manga Downloader Application"

# Install runtime dependencies
RUN apt-get update && \
    apt-get install -y python3 python3-dev libpng-dev libjpeg-dev \
    p7zip-full unrar-free libgl1 python3-pyqt5 && \
    apt-get clean

# Create non-root user
RUN useradd -m appuser
USER appuser
RUN mkdir /home/appuser/apps && chown -R appuser:appuser /home/appuser/apps

WORKDIR /home/appuser/apps

# Copy built artifacts
COPY --from=build --chown=appuser:appuser /home/appuser/apps/manga_downloader/target/manga_downloader.jar /home/appuser/apps/manga_downloader.jar
COPY --from=build --chown=appuser:appuser /home/appuser/apps/kcc /home/appuser/apps/kcc

# Create directories
RUN mkdir /home/appuser/downloaded && chown -R appuser:appuser /home/appuser/downloaded
RUN mkdir /home/appuser/apps/logs && chown -R appuser:appuser /home/appuser/apps/logs

RUN chown -R appuser:appuser /home/appuser/downloaded
RUN chown -R appuser:appuser /home/appuser/apps/logs

# Set environment variables
ENV KCC_SCRIPT=/home/appuser/apps/kcc/kcc-c2e.py
ENV VIRTUAL_ENV=/home/appuser/apps/kcc/venv
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

# Default command
ENTRYPOINT ["sh", "-c", "export PATH=/home/appuser/apps/kcc/venv/bin:$PATH && java -jar /home/appuser/apps/manga_downloader.jar \"$URL\" /home/appuser/downloaded --venv /home/appuser/apps/kcc/venv"]
