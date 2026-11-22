# Kong (open source, DB-less) in front of the election-ai Cloud Run service.
FROM kong:3.9.1

COPY kong.yml /kong/kong.yml
COPY start.sh /kong/start.sh
# (COPY --chmod needs BuildKit, which Cloud Build's default builder lacks)
USER root
RUN chmod 755 /kong/start.sh
USER kong

ENV KONG_DATABASE=off \
    KONG_DECLARATIVE_CONFIG=/tmp/kong.yml \
    KONG_PROXY_LISTEN="0.0.0.0:8080" \
    KONG_ADMIN_LISTEN=off \
    KONG_NGINX_WORKER_PROCESSES=1 \
    KONG_PROXY_ACCESS_LOG=/dev/stdout \
    KONG_PROXY_ERROR_LOG=/dev/stderr \
    KONG_TRUSTED_IPS="0.0.0.0/0,::/0" \
    KONG_REAL_IP_HEADER=X-Forwarded-For \
    KONG_HEADERS=off

EXPOSE 8080
ENTRYPOINT ["/kong/start.sh"]
