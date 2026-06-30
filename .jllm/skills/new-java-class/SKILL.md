---
name: new-java-class
description: Scaffold a new Java class that follows the project's conventions. Use when the user wants to create a new class, interface, or service in this codebase.
allowed-tools: read_file,write_file,glob
---

# New Java Class

Create a new Java class that fits the existing project, rather than a generic
stub.

## 1. Learn the conventions first

Before writing anything, inspect the codebase so the new class matches it:

- Use `glob` (e.g. `**/*.java`) to find sibling classes in the target package.
- `read_file` on a couple of nearby classes to learn the package layout,
  header/license comment, naming, formatting, logging, and dependency-injection
  or construction patterns in use.
- Confirm the correct source root and package path
  (`src/main/java/<package-as-dirs>/`).

## 2. Use the template

A starter template lives next to this skill at `templates/Class.java.tmpl`.
Read it and fill in the placeholders:

- `{{PACKAGE}}` — the fully-qualified package, e.g. `org.kendar.jllm.foo`.
- `{{CLASS_NAME}}` — the class name in PascalCase, matching the file name.
- `{{DESCRIPTION}}` — a one-line Javadoc summary of the class's purpose.
- `{{BODY}}` — fields, constructors, and methods.

Write the result to `src/main/java/<package-path>/{{CLASS_NAME}}.java`.

## 3. Rules

- The file name must exactly match the public class name.
- Keep the package declaration consistent with the directory path.
- Reuse the project's existing license/header comment if one is present in
  sibling files; otherwise omit it.
- Do not add dependencies that are not already available in the build.
- Keep the scaffold minimal and idiomatic; do not over-engineer. Add a matching
  test class only if the user asks or if the project clearly expects one.
