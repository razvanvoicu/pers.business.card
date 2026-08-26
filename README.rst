Personal Business Card
======================

A personal business card, served as a small full-stack web app: a Scala.js/Laminar frontend renders the card
(photo, name, designation, tagline, links, a QR code, and a contact form), and a ZIO HTTP backend serves it and
handles the two small pieces of dynamic behavior it needs — reporting the card's personal content and receiving
contact messages. Frontend and backend build into one Scala codebase and deploy as a single Docker image.

This document is meant to be a quick reference for future-you: how to update the card's content, how to run and
deploy it, and where things live. It intentionally does not re-explain generic Scala/ZIO/Laminar concepts.

Updating your personal info
----------------------------

The name, designation, tagline, and the four link URLs shown on the card are **not** in the source code — they
live in the external ``config.env`` file (see `Configuration`_ below) and are served to the frontend at runtime
by the backend, over ``GET /personalparticulars``. To change any of them:

.. code-block:: text

   CARD_NAME=Your Name
   CARD_DESIGNATION=Your Designation
   CARD_TAGLINE=Your tagline
   CARD_LINK1=/resume
   CARD_LINK2=https://www.linkedin.com/in/your-profile/
   CARD_LINK3=https://your-blog.example
   CARD_LINK4=https://your-portfolio.example

Edit those seven lines in ``config.env``, then redeploy (`Deploying`_) — no code change, no rebuild logic needed.
The four links are positional and line up with fixed on-card labels defined in ``CardText.scala`` (see below):
``CARD_LINK1`` → "Resume", ``CARD_LINK2`` → "LinkedIn", ``CARD_LINK3`` → "Blog", ``CARD_LINK4`` → "Portfolio".
``CARD_LINK1`` is conventionally the relative path ``/resume`` (see `Updating the visual content`_), but it's
just a URL like the other three — point it anywhere.

Updating the visual content
-----------------------------

* **Photo** — replace ``backend/src/main/resources/web/mug.jpg`` (used as-is; the card crops it to a circle).
* **QR code** — replace ``backend/src/main/resources/web/qr-code.png``.
* **Resume** — ``backend/src/main/resources/web/resume.html`` is served at ``/resume`` (linked by ``CARD_LINK1``
  by convention). Edit it directly; it's a plain static HTML file, not generated from anything.
* **Link labels** ("Resume", "LinkedIn", "Blog", "Portfolio") and every other on-card or in-dialog string (the
  contact form's title/placeholder/button text, the success/error message text) — all in
  ``frontend/src/main/scala/sgrv/fe/card/CardText.scala``. Requires a rebuild + redeploy (frontend text is
  compiled into ``main.js``, unlike the personal particulars above).
* **Layout proportions** (column/row sizing, buffer spacing, font-size fractions, the fallback shrink threshold)
  — the tunable constants in ``frontend/src/main/scala/sgrv/fe/card/GridSpec.scala``, with a comment on each
  explaining what it controls. ``GridMath.scala`` is the pure layout math that reads them; it shouldn't need
  touching just to retune a number.
* **Colors, fonts, general styling** — ``backend/src/main/resources/web/style.css``.
* **Browser tab title / installed-app identity** — ``<title>`` and the Apple app title in
  ``backend/src/main/resources/web/index.html``; ``name``/``short_name``/``description``/colors in
  ``backend/src/main/resources/web/manifest.webmanifest``; ``favicon.ico``/``icon-192.png``/``icon-512.png``
  (replace all three together if you change them).

How it's built
---------------

* Scala 3.8.4, one codebase (``shared``/``frontend``/``backend``) built by one ``sbt`` project.
* Scala.js + Laminar render the card in the browser (``frontend/src/main/scala/sgrv/fe``).
* ZIO HTTP serves both the static frontend bundle and the two small API routes (``backend/src/main/scala/sgrv/be``).
* The Scala.js linker output is copied into the backend's packaged resources at build time, so one backend build
  and one Docker image contain the whole application — there's nothing to deploy separately.
* Backend routes are auto-discovered rather than hand-wired: ``RouteDiscovery`` (``sgrv.be.core``) scans the
  ``sgrv.be`` package on the runtime classpath for objects implementing ``BackendPlugin`` and activates each one
  whose declared capability requirements it can resolve. See `Adding a backend route`_ if you ever add one.
* Google Cloud Firestore is the only external dependency, used for the contact form's stored messages and (see
  `Firestore`_) little else.

Routes
------

.. list-table::
   :header-rows: 1
   :widths: 28 48 24

   * - Route
     - Content
     - Cache policy
   * - ``/`` / ``/index.html``
     - The app shell (``index.html``)
     - ``no-cache``
   * - ``/style.css``, ``/main.js``, ``/main.js.map``
     - Stylesheet and the linked Scala.js application
     - 5 min for ``run``; 1 day for production images
   * - ``/favicon.ico``, ``/icon-192.png``, ``/icon-512.png``, ``/manifest.webmanifest``
     - PWA identity and icons
     - 5 min for ``run``; 1 day for production images
   * - ``/mug.jpg``, ``/qr-code.png``, ``/resume``
     - Card content assets
     - 5 min for ``run``; 1 day for production images
   * - ``/sw.js``
     - A no-op service worker (see the comment in ``frontend/src/main/scala/sgrv/fe/Main.scala``) — exists only
       so Chrome's PWA-installability check is satisfied
     - ``no-cache``
   * - ``GET /personalparticulars``
     - Name, designation, tagline, and the four link URLs, as JSON — sourced from ``CardConfig``
     - Default
   * - ``POST /submit-message``
     - Accepts ``{"message": "..."}``, stores it in Firestore (see `The contact form`_); ``204`` on success, full
       diagnostic text on failure
     - Default

Static routes are wired directly in ``Main`` and reserved against route-discovery conflicts; the two API routes
above are ordinary discovered ``BackendPlugin`` implementations using ``AccessPolicy.Public`` — nothing on this
card requires signing in.

The contact form
------------------

Clicking "Contact me" opens an editor (draft text persisted in the browser's ``localStorage`` so it survives a
reload); Submit closes it, shows a brief waiting spinner, and ``POST``\ s the message to ``/submit-message``.
``sgrv.be.contact.SubmitMessage`` reads the client's IP from the ``X-Forwarded-For`` header (falling back to the
raw socket address for local runs without a reverse proxy), and ``sgrv.be.contact.MessageStore`` writes a new
document to the Firestore ``Messages`` collection with fields ``message``, ``ipAddress``, and ``receivedAt``
(server timestamp). On success the frontend shows "Message successfully sent"; on failure it shows the full
response body (the backend's diagnostic text) in a scrollable pane — there's no attempt to sanitize server errors
for this route, since only you will ever see them.

Configuration
--------------

Machine- and deployment-specific settings live in one external ``config.env`` file, kept **outside** this
repository (it's not tracked, and never should be — it holds real GCP project details and your personal info).
The repository only contains an indirection to it: create one regular file directly under the Git-ignored
``.local/`` directory whose first line is:

.. code-block:: text

   APPCONFIGPATH=/path/to/shared/config.env

Loading the sbt build fails immediately, with a clear message, if that pointer is missing, empty, or points at a
nonexistent file. ``config.env`` itself uses strict ``NAME=value`` syntax (blank lines and ``#`` comments are
ignored); a duplicate, malformed, or unrecognized setting also fails the build. A complete ``config.env`` looks
like:

.. code-block:: text

   LOCAL_BASE_URL=http://localhost:<local-port>
   ARTIFACT_BASE_URL=https://<standalone-host>
   PUBLIC_BASE_URL=https://<cloud-run-host>
   ARTIFACT_PORT=<standalone-container-port>
   GCP_PROJECT_ID=<project-id>
   FIRESTORE_DATABASE_ID=<database-id>
   FIRESTORE_LOCATION=<database-location>
   GCLOUD_REGION=<cloud-run-region>
   ARTIFACT_REGISTRY_REPOSITORY=<repository-name>
   GCLOUD_SERVICE_ACCOUNT=<runtime-service-account-email>
   CARD_NAME=Your Name
   CARD_DESIGNATION=Your Designation
   CARD_TAGLINE=Your tagline
   CARD_LINK1=/resume
   CARD_LINK2=https://www.linkedin.com/in/your-profile/
   CARD_LINK3=https://your-blog.example
   CARD_LINK4=https://your-portfolio.example

The shared file is re-read on every task invocation, so an edit is picked up by the next ``sbt`` command without
needing a ``reload``. ``LOCAL_BASE_URL``/``ARTIFACT_BASE_URL``/``PUBLIC_BASE_URL`` select the origin ``sbt run``,
``sbt artifact``, and ``sbt deployGCloud`` each bind to respectively; the local one must include an explicit
port. (``PUBLIC_BASE_URL`` is currently required but not read by the app itself — see the ``TODO`` block at the
top of ``backend/src/main/scala/sgrv/be/Main.scala``.)

Running locally
-----------------

Requires JDK 21, Scala 3.8.4, and sbt 1.12.14 (and the ``config.env`` setup above). From the repository root:

.. code-block:: console

   sbt run

Then open the configured ``LOCAL_BASE_URL``. The backend binds to ``BIND_ADDRESS`` (``test.env`` sets
``0.0.0.0`` for local runs, so it's also reachable from another device on the LAN, e.g. a phone at
``http://<lan-ip-or-hostname>:<port>``). ``sbt run`` also points the Firestore client at a local emulator by
default (``test.env`` sets ``FIRESTORE_EMULATOR_HOST=localhost:8880``) rather than the real GCP project — see
`Firestore`_. On Windows, stop it by port with ``./scripts/stopapp.ps1 -Port <local-port>``.

Firestore
----------

On startup, the backend checks whether the Firestore database named ``FIRESTORE_DATABASE_ID`` exists and creates
it (Native mode, at ``FIRESTORE_LOCATION``) if not; a failed attempt is only logged as a warning, so the HTTP
server still starts, but ``/submit-message`` can't succeed until the database actually exists. See
``sgrv.be.auth.DatabaseAdmin``.

For local development, run against the emulator instead of real GCP:

.. code-block:: console

   sbt firestoreEmulator

This reads ``GCP_PROJECT_ID`` from ``config.env``, stages ``firebase.json`` (repository root; sets the emulator
port ``8880`` and its web UI port ``4000``) into a generated working directory, and starts it. Browse
``http://localhost:4000/firestore`` to inspect the ``Messages`` collection live. Data is in-memory only —
restarting the emulator clears it.

If Firestore reads/writes fail against the emulator specifically, older emulator versions only support the
*default* database; try temporarily setting ``FIRESTORE_DATABASE_ID`` to ``(default)`` in ``config.env`` as a
first troubleshooting step.

Testing
--------

.. code-block:: console

   sbt test

Runs the frontend's Scala.js tests (needs Node.js) and the backend's MUnit suite — server configuration, static
routes, the plugin-discovery/capability-resolution mechanism, request-log formatting, and ``CardConfig``/
``AppConfig`` parsing. No test calls a live Google or Firestore API. Coverage:

.. code-block:: console

   sbt clean "project backend" coverage test coverageReport

Report at ``backend/target/scala-3.8.4/scoverage-report/index.html``.

End-to-end (Selenium), against the real running app in an actual Chrome:

.. code-block:: console

   sbt e2etest/test

This is a full orchestration (see ``e2etest`` in ``build.sbt``): checks Chrome/Selenium are launchable, starts a
coverage-instrumented backend in the background, polls until it's ready, runs the suite (currently
``HomePageE2ESuite`` — loads the home page and waits for the fetched name to render), then stops the backend
regardless of outcome. ``sbt coverageReport`` afterward folds this into the same report as the unit suite.

Formatting and hygiene
------------------------

Every subproject enables deprecation/feature/unchecked/unused-code/discarded-value warnings, with SemanticDB on
for Scalafix.

.. code-block:: console

   sbt fmt        # format + remove unused code
   sbt fmtCheck   # verify only, no changes

Deploying
----------

Standalone Docker image (bakes in whatever local Application Default Credentials it finds, for testing outside a
GCP platform):

.. code-block:: console

   sbt artifact
   docker run -p <host-port>:<artifact-port> persbizcard:latest

Google Cloud Run (secret-free image; deployment config is supplied to the Cloud Run revision at deploy time
instead, from a temporary file deleted once the command finishes):

.. code-block:: console

   sbt deployGCloud

Reads the project/region/repository/service-account from ``config.env``, builds, pushes to Artifact Registry, and
deploys. Needs Docker and an authenticated ``gcloud`` CLI locally, and Firestore permissions on the configured
runtime service account (`Application Default Credentials`_ below covers local ADC for ``sbt run``/``artifact``;
Cloud Run itself uses the service's own workload identity, no local credentials involved).

Both tasks tag the image ``persbizcard:<version>`` (``ThisBuild / version`` in ``build.sbt``, currently ``1.0``)
and stage a full Docker build context (app + dependency JARs, ``prod.env``, the ``runApp`` entrypoint, the
``Dockerfile``) under ``backend/target/``. ``dockerPlatform`` in ``build.sbt`` (default ``linux/amd64``) sets the
image's target platform independently of the build machine.

Application Default Credentials
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Locally (``sbt run``, and for the ADC baked into ``sbt artifact``'s standalone image):

.. code-block:: console

   gcloud auth application-default login --impersonate-service-account=<Firestore service account>

On Cloud Run, the deployed service's own attached identity (``GCLOUD_SERVICE_ACCOUNT``) is used instead — no
credentials file involved, and the standalone artifact's ADC file is deliberately never included in the Cloud Run
image (an empty ``adc/`` directory is staged so the Dockerfile's ``COPY`` stays valid).

Repository layout
-------------------

.. code-block:: text

   build.sbt
   firebase.json
   project/AppConfigBuild.scala
   project/LocalConfigBuild.scala
   project/PublicBaseUrlBuild.scala
   project/Dependencies.scala
   project/plugins.sbt
   frontend/src/main/scala/sgrv/fe/Main.scala
   frontend/src/main/scala/sgrv/fe/card/           # the card itself: layout, text, math
   backend/src/main/scala/sgrv/be/Main.scala
   backend/src/main/scala/sgrv/be/BackendEnvironment.scala
   backend/src/main/scala/sgrv/be/auth/            # AppConfig, DatabaseAdmin, GoogleFuture (misnamed — see TODO)
   backend/src/main/scala/sgrv/be/card/            # GET /personalparticulars
   backend/src/main/scala/sgrv/be/contact/         # POST /submit-message, Firestore Messages writes
   backend/src/main/scala/sgrv/be/core/            # BackendPlugin/RouteDiscovery/Capability — generic
   backend/src/main/resources/prod.env
   backend/src/main/resources/test.env
   backend/src/main/resources/Dockerfile
   backend/src/main/resources/web/                 # mug.jpg, qr-code.png, resume.html, style.css, ...
   backend/src/test/scala/
   frontend/src/test/scala/
   e2etest/src/test/scala/
   scripts/stopapp.ps1

Adding a backend route
------------------------

Place a top-level object implementing ``BackendPlugin`` anywhere under ``sgrv.be``; it's discovered automatically
— nothing to register in ``Main``. Every current route uses ``AccessPolicy.Public`` (there's no sign-in on this
card), so the minimal shape is:

.. code-block:: scala

   package sgrv.be.example

   import sgrv.be.core.{AccessPolicy, BackendPlugin, CapabilitySet, RequestContext}
   import zio.http.{Method, Response, Routes, handler}

   object Example extends BackendPlugin:
     type Requires = Any

     override val id = "example"
     override val requirements: CapabilitySet[Requires] = CapabilitySet.empty
     override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
     override val routes: Routes[Requires & RequestContext, Nothing] =
       Routes(Method.GET / "example" -> handler(Response.text("example")))

If the route needs a service (like ``sgrv.be.contact.SubmitMessage`` needs ``MessageStore``), declare it as
``Requires``, add a ``Capability`` for it to ``BackendCapabilities`` in ``BackendEnvironment.scala``, provide its
``ZLayer`` in ``Main``'s ``backendLayer``, and require it via ``CapabilitySet.one(BackendCapabilities.yourThing)``
— a plugin missing a declared capability is skipped (logged, not fatal) rather than crashing the server. Combine
multiple requirements with ``++``; the resulting ``CapabilitySet[A & B]`` resolves to a ``ZEnvironment[A & B]``.
