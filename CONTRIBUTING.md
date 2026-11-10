# Contributing

Thanks for helping with Bit's RPS.

## Reporting bugs and asking for features

Open an issue and include the Bit's RPS version, your Redis server version, your Java version, and the steps to reproduce the problem. For security problems, follow [SECURITY.md](SECURITY.md) instead.

## Building

You need Java 21, Maven and Docker.

```
mvn clean verify
```

This compiles the project, runs the tests, writes a coverage report to `target/site/jacoco`, and runs SpotBugs. A change must pass `mvn clean verify` before it is merged.

## Making a change

1. Fork the repository and create a branch from `master`.
2. Follow the style of the surrounding code. Use braced `if` statements and early returns, and keep changes focused. Please do not mix refactors with fixes.
3. Add or update tests for the behaviour you change. Request, timeout and subscription changes especially need tests. The tests start a Redis container, so Docker has to be running.
4. Add a line to `CHANGELOG.md` under the next unreleased version. Mark anything that breaks public API as breaking.
5. Update the README if you change something users see, such as commands, permissions, config keys or the API.
6. Open a pull request against `master` and describe what changed and why.

Commit messages follow the pattern used in the history, a short type and summary such as `fix: ...`, `feat: ...`, `refactor: ...` or `chore: ...`.

## Releasing

Maintainers release by setting the version in `pom.xml` and pushing a tag named exactly the version, with no prefix (for example `2.0.0`). The release workflow checks that the tag matches the pom, deploys the artifacts and creates the GitHub release.

## License

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
