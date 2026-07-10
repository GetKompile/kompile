# Generic kompile "spin" image: packages one product out of a kompile
# distribution (build-dist.sh output). Build context = a staging dir holding
# the extracted dist under dist/ plus spin-entrypoint.sh (assembled by
# scripts/build-spins.sh). PRODUCT selects what runs: cli | app-main |
# model-staging. Works with both native-image dists (bin/kompile-server) and
# exec-jar dists (runs on the bundled jlink runtime/).
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE}
ARG PRODUCT
RUN dnf install -y zlib libgomp findutils shadow-utils && dnf clean all \
    && useradd --system --home /opt/kompile kompile
COPY dist/ /opt/kompile/
COPY spin-entrypoint.sh /opt/kompile/spin-entrypoint.sh
RUN chmod +x /opt/kompile/spin-entrypoint.sh && chown -R kompile /opt/kompile
ENV KOMPILE_PRODUCT=${PRODUCT} KOMPILE_HOME=/opt/kompile
USER kompile
WORKDIR /opt/kompile
# app-main serves 8080, model-staging serves 8091 (metadata only)
EXPOSE 8080 8091
ENTRYPOINT ["/opt/kompile/spin-entrypoint.sh"]
