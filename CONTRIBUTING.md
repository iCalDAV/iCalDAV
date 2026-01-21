# Contributing to iCalDAV

Thank you for your interest in contributing to iCalDAV! This document provides guidelines and instructions for contributing.

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## How to Contribute

### Reporting Bugs

Before creating a bug report, please check existing issues to avoid duplicates.

When filing a bug report, include:

- **Version**: Which version of iCalDAV are you using?
- **Environment**: JDK version, OS, Android version (if applicable)
- **Description**: Clear description of the bug
- **Steps to Reproduce**: Minimal steps to reproduce the issue
- **Expected Behavior**: What you expected to happen
- **Actual Behavior**: What actually happened
- **ICS Sample**: If applicable, include a (sanitized) ICS sample

### Suggesting Features

Feature requests are welcome! Please include:

- **Use Case**: Describe the problem you're trying to solve
- **Proposed Solution**: Your idea for how to solve it
- **Alternatives**: Any alternative solutions you've considered
- **RFC Reference**: If the feature relates to an RFC, include the reference

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Write tests** for any new functionality
3. **Ensure all tests pass**: `./gradlew test`
4. **Run code quality checks**: `./gradlew detekt`
5. **Update documentation** if needed
6. **Follow the code style** (see below)
7. **Submit a pull request**

## Development Setup

### Prerequisites

- JDK 17 or higher (required - project targets JVM 17)
- Gradle 8.x (wrapper included)

### Building

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/iCalDAV.git
cd iCalDAV

# Build
./gradlew build

# Run tests
./gradlew test

# Run code quality checks
./gradlew detekt
```

### Project Structure

```
iCalDAV/
├── icaldav-core/       # ICS parsing, generation, RRULE
├── icaldav-client/     # CalDAV/WebDAV client, discovery, ICS subscriptions
├── icaldav-sync/       # Sync engine with offline support
└── icaldav-android/    # Android CalendarContract mapper
```

## Code Style

### Kotlin Style Guide

We follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) with these additions:

- **Line length**: 120 characters max
- **Indentation**: 4 spaces (no tabs)
- **Imports**: No wildcard imports
- **Trailing commas**: Use in multi-line declarations

### Naming Conventions

```kotlin
// Classes: PascalCase
class ICalParser

// Functions: camelCase
fun parseEvent()

// Constants: SCREAMING_SNAKE_CASE
const val MAX_RRULE_INSTANCES = 1000

// Properties: camelCase
val eventCount: Int
```

### Documentation

- All public APIs must have KDoc comments
- Include `@param`, `@return`, `@throws` where applicable
- Reference RFCs when implementing standard behavior

```kotlin
/**
 * Parses an iCalendar (ICS) string into calendar objects.
 *
 * Implements RFC 5545 Section 3 (iCalendar Object).
 *
 * @param icsContent The raw ICS content to parse
 * @return ParseResult containing events, todos, and any parse errors
 * @throws IllegalArgumentException if icsContent is blank
 * @see [RFC 5545](https://tools.ietf.org/html/rfc5545)
 */
fun parse(icsContent: String): ParseResult
```

## Testing Guidelines

### Test Structure

```kotlin
@DisplayName("ICalParser Tests")
class ICalParserTest {

    @Nested
    @DisplayName("Event Parsing")
    inner class EventParsingTests {

        @Test
        fun `parses basic event`() {
            // Arrange
            val ics = "..."

            // Act
            val result = parser.parse(ics)

            // Assert
            assertEquals(1, result.events.size)
        }
    }
}
```

### What to Test

- **Happy path**: Normal usage
- **Edge cases**: Empty input, max values, boundary conditions
- **Error handling**: Invalid input, network errors
- **RFC compliance**: Verify behavior matches specifications

### Test Naming

Use descriptive names with backticks:

```kotlin
@Test
fun `parses event with RRULE and EXDATE`() { }

@Test
fun `returns error for invalid DTSTART format`() { }

@Test
fun `handles timezone change during DST transition`() { }
```

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style (formatting, no logic change)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Build, CI, dependencies

### Examples

```
feat(parser): add support for VTIMEZONE parsing

fix(rrule): handle COUNT=0 edge case

docs(readme): add installation instructions

test(caldav): add sync conflict resolution tests
```

## Pull Request Process

1. **Create a branch**: `git checkout -b feat/my-feature`
2. **Make changes**: Write code and tests
3. **Commit**: Use conventional commit format
4. **Push**: `git push origin feat/my-feature`
5. **Open PR**: Fill out the PR template
6. **Review**: Address reviewer feedback
7. **Merge**: Maintainer will merge after approval

### PR Checklist

- [ ] Tests pass locally (`./gradlew test`)
- [ ] Code follows style guidelines
- [ ] Documentation updated (if applicable)
- [ ] Commit messages follow conventions
- [ ] PR description explains changes
- [ ] Related issues linked

## Release Process

Releases are managed by maintainers. We use [Semantic Versioning](https://semver.org/):

- **MAJOR**: Breaking API changes
- **MINOR**: New features (backward compatible)
- **PATCH**: Bug fixes (backward compatible)

## Getting Help

- **Questions & Bugs**: Open an [Issue](https://github.com/iCalDAV/iCalDAV/issues)
- **Security**: See [SECURITY.md](SECURITY.md)

## Recognition

Contributors are recognized in:

- Release notes
- README acknowledgments
- GitHub contributors page

Thank you for contributing to iCalDAV!