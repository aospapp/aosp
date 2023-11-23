FROM debian:buster-slim AS docker_vmm

ENV container docker
ENV LC_ALL C.UTF-8
ENV DEBIAN_FRONTEND noninteractive

SHELL [ "/bin/bash", "-c" ]

# Set up the user to be the same as the user creating the container.  Not
# strictly necessary, but this way all the permissions of the generated files
# will match.

ARG USER
ARG UID

ENV USER $USER
ENV HOME /home/$USER
ENV CUSTOM_MANIFEST ""

RUN apt update \
    && apt install -y sudo

RUN useradd -m -s /bin/bash $USER -u $UID -d $HOME \
    && passwd -d $USER \
    && echo "$USER ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers

RUN mkdir /source && chown -R $USER /source
RUN mkdir /output && chown -R $USER /output
RUN mkdir /working && chown -R $USER /working
RUN mkdir /static && chown -R $USER /static

USER $USER
WORKDIR $HOME

COPY --chown=$USER x86_64-linux-gnu/manifest.xml /static/x86_64-linux-gnu/manifest.xml
COPY --chown=$USER aarch64-linux-gnu/manifest.xml /static/aarch64-linux-gnu/manifest.xml
COPY --chown=$USER custom.xml /static/custom.xml
COPY --chown=$USER policy-inliner.sh /static/policy-inliner.sh
COPY --chown=$USER rebuild-internal.sh /static/rebuild-internal.sh

RUN TOOLS_DIR=/static/tools /static/rebuild-internal.sh install_custom_scripts install_packages

VOLUME /source
VOLUME /working
VOLUME /output

FROM docker_vmm AS docker_vmm_persistent

ENV container docker
ENV LC_ALL C.UTF-8
ENV DEBIAN_FRONTEND noninteractive

SHELL [ "/bin/bash", "-c" ]

USER root

# Containers built from this image are meant to persist, once started.  A user
# account is created on them where the work of building crosvm is carried out,
# persistently.

RUN apt-get update \
    && apt-get install -y systemd \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* \
    && rm -f /var/run/nologin

RUN rm -f /lib/systemd/system/multi-user.target.wants/* \
    /etc/systemd/system/*.wants/* \
    /lib/systemd/system/local-fs.target.wants/* \
    /lib/systemd/system/sockets.target.wants/*udev* \
    /lib/systemd/system/sockets.target.wants/*initctl* \
    /lib/systemd/system/sysinit.target.wants/systemd-tmpfiles-setup* \
    /lib/systemd/system/systemd-update-utmp*

VOLUME [ "/sys/fs/cgroup" ]

CMD ["/lib/systemd/systemd"]

RUN apt update \
    && apt install -y apt-utils sudo dpkg-dev coreutils \
       openssh-server openssh-client psmisc iptables iproute2 dnsmasq \
       net-tools rsyslog equivs

RUN apt install -y dialog

RUN sed -i -r -e 's/^#{0,1}\s*PasswordAuthentication\s+(yes|no)/PasswordAuthentication yes/g' /etc/ssh/sshd_config \
    && sed -i -r -e 's/^#{0,1}\s*PermitEmptyPasswords\s+(yes|no)/PermitEmptyPasswords yes/g' /etc/ssh/sshd_config \
    && sed -i -r -e 's/^#{0,1}\s*ChallengeResponseAuthentication\s+(yes|no)/ChallengeResponseAuthentication no/g' /etc/ssh/sshd_config \
    && sed -i -r -e 's/^#{0,1}\s*UsePAM\s+(yes|no)/UsePAM no/g' /etc/ssh/sshd_config
