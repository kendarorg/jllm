package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellWritesTest {

    @Test
    void redirectionsAreWrites() {
        assertEquals(List.of(".env"), ShellWrites.targets("echo SECRET=1 > .env"));
        assertEquals(List.of("log.txt"), ShellWrites.targets("echo x >> log.txt"));
        assertEquals(List.of("err.txt"), ShellWrites.targets("make 2> err.txt"));
        assertEquals(List.of("all.txt"), ShellWrites.targets("make &> all.txt"));
        assertEquals(List.of("a b.txt"), ShellWrites.targets("echo x > \"a b.txt\""));
        assertEquals(List.of("a.txt"), ShellWrites.targets("echo hello>a.txt"));
    }

    @Test
    void descriptorDuplicationAndDiscardAreNotWrites() {
        assertEquals(List.of(), ShellWrites.targets("make 2>&1"));
        assertEquals(List.of(), ShellWrites.targets("ls > /dev/null 2>&1"));
        assertEquals(List.of(), ShellWrites.targets("dir > NUL"));
        assertEquals(List.of(), ShellWrites.targets("cat a.txt | grep x"));
    }

    @Test
    void writingCommandsAreWrites() {
        assertEquals(List.of(".env"), ShellWrites.targets("echo x | tee -a .env"));
        assertEquals(List.of("a", "b"), ShellWrites.targets("touch a b"));
        assertEquals(List.of(".env"), ShellWrites.targets("cp template.txt .env"));
        assertEquals(List.of("conf/.env"), ShellWrites.targets("ls && mv -f x conf/.env"));
        assertEquals(List.of(".env"), ShellWrites.targets("dd if=/dev/zero of=.env count=1"));
        assertEquals(List.of(".env"), ShellWrites.targets("sed -i 's/a/b/' .env"));
        assertEquals(List.of(), ShellWrites.targets("sed 's/a/b/' .env"));
        assertEquals(List.of(".env"), ShellWrites.targets("/usr/bin/tee .env"));
    }

    @Test
    void powershellCmdletsAreWrites() {
        assertEquals(List.of(".env"), ShellWrites.targets("Set-Content -Path .env -Value 'SECRET=1'"));
        assertEquals(List.of(".env"), ShellWrites.targets("'SECRET=1' | Out-File .env"));
        assertEquals(List.of(".env"), ShellWrites.targets("Add-Content -Path:.env -Value x"));
        assertEquals(List.of(".env"), ShellWrites.targets("New-Item -ItemType File -Path .env -Force"));
        assertEquals(List.of(".env"), ShellWrites.targets("Copy-Item a.txt -Destination .env"));
        assertEquals(List.of(".env"), ShellWrites.targets("Move-Item a.txt .env"));
    }

    @Test
    void readOnlyCommandsWriteNothing() {
        assertEquals(List.of(), ShellWrites.targets("cat .env"));
        assertEquals(List.of(), ShellWrites.targets("grep -r SECRET ."));
        assertEquals(List.of(), ShellWrites.targets("Get-Content .env"));
        assertEquals(List.of(), ShellWrites.targets(""));
        assertEquals(List.of(), ShellWrites.targets(null));
    }
}
