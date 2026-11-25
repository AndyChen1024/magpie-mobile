FROM hexpm/elixir:1.20.2-erlang-29.0.2-debian-bookworm-20260623-slim@sha256:2f7018f129c268e439a4e4054d9487a85ad4c2f05141e9347a4442e8a4b3712c AS build
WORKDIR /app
ENV MIX_ENV=prod
COPY mix.exs mix.lock ./
RUN mix local.hex 2.7.0 --force && mix local.rebar --force
RUN --mount=type=cache,target=/root/.hex HEX_HTTP_CONCURRENCY=1 HEX_HTTP_TIMEOUT=120 mix deps.get --only prod
COPY config config
COPY lib lib
RUN mix compile && mix release

FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
RUN apt-get update && apt-get install -y --no-install-recommends libstdc++6 libsctp1 openssl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/_build/prod/rel/paseo_relay ./
ENV HOME=/app \
    PASEO_RELAY_HOST=0.0.0.0 \
    PASEO_RELAY_PORT=4000 \
    PASEO_RELAY_DRAIN=false
EXPOSE 4000
ENTRYPOINT ["/app/bin/paseo_relay"]
CMD ["start"]
