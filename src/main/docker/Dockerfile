FROM openjdk:8-jdk
RUN useradd -ms /bin/bash stanford
COPY start.sh /home/stanford/start.sh
RUN chmod +x /home/stanford/start.sh
COPY *.jar /home/stanford
USER stanford
WORKDIR /home/stanford
CMD ["/home/stanford/start.sh"]


