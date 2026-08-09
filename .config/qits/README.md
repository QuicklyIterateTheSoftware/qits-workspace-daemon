# Why there is no `ci-event-release.yml` here

This repository has a push pipeline (`ci-post-receive.yml`) and no release pipeline. That is a
recorded block, not an oversight, and it is one line of YAML away from being fixed.

## The block

A release pipeline selects the event it reacts to:

    event: SCMRelease
    when:
      - repository: { exact: qits-workspace-daemon }

`SCMRelease.repository` carries the repository **row id**, and on this platform this repository's
row id is the UUID `f62a44aa-f651-4779-9cc3-35a8d62c6f18` rather than its name. Measured:
`GET /workspaces/api/workspaces?repositoryId=qits-workspace-daemon` answers 404, and the same call
with the UUID answers 200.

`when:` supports only literal `exact` and `prefix`, so the matcher above would have to spell that
UUID. A UUID is generated per platform: the file would match on this deployment, match nothing on
any other, and go stale the moment the row is recreated. A committed file must not carry a value
that is true of one machine.

So the choice was a release pipeline that is wrong everywhere else, or none. None, plus this note.

## What to do

Fix the row id first — make this repository's row id its name, `qits-workspace-daemon`, as every
other repository's is. Then add `ci-event-release.yml` with

    when:
      - repository: { exact: qits-workspace-daemon }

The sibling `qits-projects-daemon` already carries the file this repository should get. Copy it and
change the name segment from `projects-daemon` to `workspace-daemon` in both the `artifacts:`
declaration and the build — the image build, the tag checkout and the CalVer validation are the same
here.

Correcting the row id is a destructive operation on a live platform and is a separate job.

## What is not blocked

`ci-post-receive.yml` is unaffected: a push pipeline is selected by the repository the push arrived
on, not by an event payload. Every green push already builds and pushes
`…/workspace-daemon:<sha>`. What is missing is only the version-tagged coordinate and the
`SoftwareRelease` announcement that goes with it.
