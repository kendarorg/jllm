lib-ext: the GraalJS libraries of run_js
========================================

The jllm jars do not contain GraalJS. The jars in this folder (polyglot, js, regex and the isolate add-ons of the four
platforms) are put on the class path by run-cli and run-web. The zip ships this folder empty: setup.sh / setup.ps1
(released next to the zip) or fetch-graaljs fill it with the edition you choose. graaljs-<edition>.manifest lists every
jar with its SHA-256; manifests/ holds the manifests of both editions.

* Empty folder: jllm starts normally, run_js is not offered and one warning in the log says why.
* Swap edition or restore the set: fetch-graaljs.sh | fetch-graaljs.cmd | fetch-graaljs.ps1  <community|oracle>
  (one directory up). It empties lib-ext/*.jar first, downloads from Maven Central, verifies the SHA-256 of each jar and
  fails, deleting the file, on a mismatch. The oracle set declares terms (GraalVM Free Terms and Conditions) that you must
  read and accept: pass --accept-oracle-terms (-AcceptOracleTerms on Windows).
* Other version: replace the jars by hand; the edition label is for diagnostics only and never changes behaviour.
* manifests/ holds the manifests of both editions for the fetch script. Do not remove it.

Licences are those declared by the poms of the shipped version; see NOTICE.
