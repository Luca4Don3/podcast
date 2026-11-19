FROM ubuntu:24.04
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends samba \
 && useradd --system --no-create-home podcast_fixture \
 && mkdir -p /fixture/media \
 && printf '0123456789abcdef' > /fixture/media/seek.mp3 \
 && printf 'Not audio' > /fixture/media/notice.txt \
 && chmod -R a+rX /fixture
COPY smb.conf /fixture-smb.conf
EXPOSE 445/tcp
# Test-only account; never provide production credentials to this fixture.
CMD ["/bin/sh","-ec","printf '%s\\n%s\\n' \"$PODCAST_TEST_SMB_PASSWORD\" \"$PODCAST_TEST_SMB_PASSWORD\" | smbpasswd -s -a podcast_fixture -c /fixture-smb.conf; exec smbd --foreground --no-process-group --debug-stdout --configfile=/fixture-smb.conf"]
