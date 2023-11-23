# FLEDGE FakeWebServer library

Design document: go/fake-fledge.

This folder contains a client library for the FLEDGE test server, a hosted
service from Google used in CTS tests.

Due to CTS requirements (non-rooted build) and product requirements (real
webserver with adtech enrollment) a real server must be used. This client lib
implements the `FakeWebServer` interface for clients to write CTS tests
against as if it was a `MockWebServer`.

The online service will return responses that the client has requested.
