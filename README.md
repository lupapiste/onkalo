# onkalo

Onkalo archive system front-end

## Developing

Uses Stuart Sierra's [Reloaded Workflow][reloaded] and the [Duct mini framework][duct]

[reloaded]:  http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded
[duct]: https://github.com/weavejester/duct/

### Overriding config.edn

You can provide your own local config by creating a file called `config-local.edn` to the project root. This file is
ignored by git. Please don't commit your personal settings to `config.edn`. The local config will be meta-merged with
the general config, so the map hierarchy must be identical.

Remember to use a shared `sessionkey` file with the lupapiste project. See the README of that repository for further
info. To login to onkalo, first login to a locally running lupapiste instance.

### Elasticsearch dependency

You will need Elasticsearch 7.17.1 to run Onkalo. The base config assumes that Elasticsearch will be available at
`localhost:9200`. It can be installed e.g. from Homebrew (`elasticsearch@7`) or you can run it with docker:

Run Elasticsearch and expose the right ports the first time (see also the utility functions in
test-util.elastic-fixture)

    docker run -d -p 9200:9200 elasticsearch:7.17.1

The port publishing format is

    -p <host port>:<container port>

If you need to restart it, you can check the docker container id

    docker ps -a

And start it again

    docker start <containerid>

Alternatively, you can use `docker-compose`:

    docker-compose up

For more on `docker-compose`, see below.

### Investigating what is going on in Elasticsearch with Kibana

The docker-compose setup also runs Kibana, which is an analytics / exploration tool for Elasticsearch. You can access
the UI at http://localhost:5601

### GCS Dependency

Onkalo needs a connection to a Google Cloud Storage to save or retrieve document objects. The base config is in
`config.edn`, but you'll have to add your personal service account keys in `config-local.edn` to be able to connect:

    {:gcs {:service-account-file "/path/to/credentials_file.json"}}

Note that the storage is only accessed when actually uploading or retrieving a document, so if you're e.g.
only working with Elasticsearch searches, storage use is not mandatory.

### Google Pub/Sub dependency

Onkalo uses Google Pub/Sub to send preview generation messages into the cloud. The credentials used are the same as for
GCS. No specific configuration for Pub/Sub is necessary, just make sure your credentials can publish to Pub/Sub. 
If you want to develop with a local Pub/Sub emulator, e.g. when developing vaahtera-laundry, you should add the 
endpoint in `config-local.edn`:

    :pubsub {:endpoint "127.0.0.1:8085"}

See siirappi repo for a Docker image of the Pub/Sub emulator or run it with:

    gcloud beta emulators pubsub start --host-port=0.0.0.0:8085

### ArtemisMQ dependency

Onkalo uses ArtemisMQ to send messages to, and receive them from, other related services. By default, when in dev mode,
Onkalo uses an embedded Artemis running alongside the application. Another option is to use a Docker container. To do this,
add the following to your `config-local.edn`:

    {:jms {:embedded? false
           :username "onkalo"
           :password "onkalo"
           :broker-url "tcp://0.0.0.0:61616"}}

You can run the Artemis container with `docker-compose`:

    docker-compose -f docker-artemis.yml up

For more on `docker-compose`, see below.

### Using Docker

By default, running `docker-compose up` only starts the elasticsearch container. In order to start both elasticsearch
and Artemis, run

    docker-compose -f docker-compose.yml -f docker-artemis.yml up

Likewise, when shutting down the containers, list the files used with `docker-compose up`:

    docker-compose -f docker-compose.yml -f docker-artemis.yml down

The necessary changes to `config-local.edn` are listed above.

### Running in dev mode

Start a repl:

    lein repl

Start the app:

    (go)

Reload changes with:

    (reset)

### Front-end development

Develop front-end with figwheel:

    lein figwheel

You should symlink document-search-commons under checkouts for front-end development, as figwheel expects to access
sources from there as well. Styles are also located under document-search-commons. Run sass build for continuos css
build when altering styles:

    lein with-profile dev sass4clj auto

### Testing via UI

1. Start both (1) Lupapiste and (2) Onkalo

To do that ensure that:
* Elastic search 7 is up and running (see above)
* PubSub is up and running (see above)
* You have the same sessionkey in Lupapiste and Onkalo root dir (see above)
* document-search-commons repository is symlinked into checkouts folder
* Sass files are at least once 
* Front-end build is running

2. Ensure test user access rights
2.1 Ensure that the organization have Digital Archive ("Sähköinen arkisto") activated
2.2 Ensure that the user (of organization having Digital Archive activated) have Archivist ("Arkistonhoitaja") role

3. Authenticate
3.1 Log in via Lupapiste (by default in http://localhost:8000)
3.2 Navigate to Onkalo db url http://localhost:8012 (session is shared)

### i18n

To extract strings for translation, create a symlink to translations.txt under document-search-commons

    ln -s ../document-search-commons/resources/translations.txt resources/translations.txt

 and then run

    lein extract-strings

## API docs

See [docs/http-json-apis-en.md](docs/http-json-apis-en.md) (English) or
[docs/http-json-apis-en.md](docs/http-json-apis-fi.md) (Finnish).

## License

Copyright © 2023 Cloudpermit Oy

Distributed under the European Union Public Licence (EUPL) version 1.2.
