lib-ext: the GraalJS libraries of run_js
========================================

The jllm jars do not contain GraalJS. The jars in this folder (polyglot, js, regex and the isolate add-ons of the four
platforms) are put on the class path by run-cli and run-web. The zip ships this folder empty: setup.sh / setup.ps1
(released next to the zip) or fetch-graaljs fill it with the edition you choose. graaljs-<edition>.manifest lists every
jar with its SHA-256; manifests/ holds the manifests of both editions.

* Empty folder: jllm starts normally, run_js is not offered and one warning in the log says why.
* Swap edition or restore the set: fetch-graaljs.sh | fetch-graaljs.cmd | fetch-graaljs.ps1  <community|oracle>
  (one directory up). It empties lib-ext/*.jar first (not the SQL drivers, not lib-ext/sql), downloads from Maven Central, verifies the SHA-256 of each jar and
  fails, deleting the file, on a mismatch. The oracle set declares terms (GraalVM Free Terms and Conditions) that you must
  read and accept: pass --accept-oracle-terms (-AcceptOracleTerms on Windows).
* Other version: replace the jars by hand; the edition label is for diagnostics only and never changes behaviour.
* manifests/ holds the manifests of both editions for the fetch script. Do not remove it.

SQL store: lib-ext/sql holds the jars of the SQL store (shipped; jllm.store=file turns it off). The MySQL and PostgreSQL
drivers are not shipped: setup, or fetch-sql-driver (.sh/.cmd/.ps1 one directory up) downloads them into this folder
(mysql-connector-j-*.jar, postgresql-*.jar), verified against manifests/mysql.manifest and manifests/postgres.manifest.
MySQL is GPLv2 with the FOSS exception and asks for acceptance. fetch-graaljs never removes them. Only the driver of
jllm.sql.url is used. SQLite is bundled and needs nothing.

Licences are those declared by the poms of the shipped version; see NOTICE.
