FROM debian:bookworm-slim

# Install required packages
RUN apt-get update && apt-get install -y \
    python3.11 \
    python3-pip \
    python3.11-venv \
    python3.11-dev \
    curl \
    wget \
    git \
    build-essential \
    libz-dev \
    zlib1g-dev \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
WORKDIR /tmp
COPY requirements.txt .
RUN python3.11 -m venv /opt/venv && \
    /opt/venv/bin/pip install --upgrade pip && \
    /opt/venv/bin/pip install -r requirements.txt && \
    rm requirements.txt

# Set the virtual environment path
ENV PATH="/opt/venv/bin:$PATH"

# Install GraalVM with Native Image for Linux builds
RUN wget -q https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz && \
    tar -xzf graalvm-jdk-25_linux-x64_bin.tar.gz -C /opt && \
    rm graalvm-jdk-25_linux-x64_bin.tar.gz && \
    mv /opt/graalvm-jdk-25* /opt/graalvm

ENV JAVA_HOME=/opt/graalvm
ENV GRAALVM_HOME=/opt/graalvm
ENV PATH=$PATH:$JAVA_HOME/bin

# Install Maven - try multiple mirrors
RUN wget -q https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz || \
    wget -q https://dlcdn.apache.org/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz || \
    wget -q https://downloads.apache.org/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz && \
    tar -xzf apache-maven-3.9.11-bin.tar.gz -C /opt && \
    rm apache-maven-3.9.11-bin.tar.gz && \
    mv /opt/apache-maven-3.9.11 /opt/maven

ENV PATH=$PATH:/opt/maven/bin

# Copy project files
WORKDIR /Mythic
COPY main.py main.py
COPY woopsie woopsie

# Pre-download Maven dependencies
WORKDIR /Mythic/woopsie/agent_code
RUN mvn dependency:go-offline -B || true

# Final setup
WORKDIR /Mythic

ENV PYTHONPATH=$PYTHONPATH:/Mythic/woopsie/agent_code

CMD ["python3.11", "main.py"]
