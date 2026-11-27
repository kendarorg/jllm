package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.instructions.SystemPromptFiles;
import org.kendar.llm.harness.plugin.FilePluginStorage;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.settings.AgentRepository;
import org.kendar.llm.harness.settings.ConfigProvider;
import org.kendar.llm.harness.settings.FileSettingsSource;
import org.kendar.llm.harness.settings.InstructionRepository;
import org.kendar.llm.harness.settings.SettingsSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The configuration of the command line: the settings files ({@link FileSettingsSource}), {@code SYSTEM.md} and
 * {@code APPEND_SYSTEM.md} ({@link SystemPromptFiles}). The instruction files and the skill files are named by the
 * settings and read by the harness; the agents are the settings' {@code agents}.
 * <p>
 * <b>Folder trust.</b> A provider with a project directory is built only once the folder is trusted
 * ({@link FolderTrustGate}); before that, {@link #userOnly} reads nothing of the project.
 */
public final class FileConfigProvider implements ConfigProvider {

    private final Path projectDir;
    private final Path explicitSettings;
    private final boolean projectSystemPrompt;
    private final ObjectMapper mapper;

    private FileConfigProvider(Path projectDir, Path explicitSettings, boolean projectSystemPrompt, ObjectMapper mapper) {
        this.projectDir = projectDir;
        this.explicitSettings = explicitSettings;
        this.projectSystemPrompt = projectSystemPrompt;
        this.mapper = mapper;
    }

    /** The user's own files only: no project layer, no project {@code .jllm/} (used before the folder is trusted). */
    public static FileConfigProvider userOnly(Path explicitSettings, ObjectMapper mapper) {
        return new FileConfigProvider(null, explicitSettings, false, mapper);
    }

    /**
     * The files of a trusted project folder as well.
     *
     * @param projectSystemPrompt whether the project's {@code SYSTEM.md} may replace the system prompt (approved
     *                            separately, see {@link FolderTrustGate#projectSystemPrompt})
     */
    public static FileConfigProvider trusted(Path projectDir, Path explicitSettings, boolean projectSystemPrompt,
                                             ObjectMapper mapper) {
        if (projectDir == null) throw new IllegalArgumentException("A trusted provider needs its project folder");
        return new FileConfigProvider(projectDir, explicitSettings, projectSystemPrompt, mapper);
    }

    @Override
    public SettingsSource settings() {
        return new FileSettingsSource(explicitSettings, projectDir);
    }

    @Override
    public InstructionRepository instructions() {
        SystemPromptFiles files = SystemPromptFiles.load(projectDir, projectSystemPrompt);
        return new InstructionRepository() {
            @Override
            public List<Instruction> list(Scope scope, String owner) {
                return List.of();
            }

            @Override
            public Optional<Instruction> find(Scope scope, String owner, String name) {
                if (scope != Scope.SYSTEM) return Optional.empty();
                String text = SYSTEM_REPLACE.equals(name) ? files.replace() : SYSTEM_APPEND.equals(name) ? files.append() : null;
                return text == null ? Optional.empty() : Optional.of(new Instruction(scope, "", name, text));
            }

            @Override
            public Instruction write(Scope scope, String owner, String name, String content) {
                throw new UnsupportedOperationException("The files are edited by hand");
            }

            @Override
            public boolean delete(Scope scope, String owner, String name) {
                return false;
            }

            @Override
            public void deleteOwned(Scope scope, String owner) {
            }
        };
    }

    @Override
    public AgentRepository agents() {
        return AgentRepository.NONE; // the agents are the settings' own
    }

    @Override
    public SkillRepository skills() {
        return NoSkills.INSTANCE; // the skill files are named by the settings and read by the harness
    }

    @Override
    public PluginStorages pluginStorages() {
        Path base = projectDir != null ? projectDir : Path.of("").toAbsolutePath();
        return new FilePluginStorage(base.resolve(".jllm").resolve("plugin-data"), mapper);
    }

    /** No skill of the web kind: the command line has the skills of the settings, read-only here. */
    private static final class NoSkills implements SkillRepository {
        static final NoSkills INSTANCE = new NoSkills();

        public List<Skill> global() {
            return List.of();
        }

        public List<Skill> own(String userId) {
            return List.of();
        }

        public Optional<Skill> findGlobal(String name) {
            return Optional.empty();
        }

        public Optional<Skill> findOwn(String userId, String name) {
            return Optional.empty();
        }

        public Skill writeGlobal(String name, String description, String content) {
            throw new UnsupportedOperationException("Skills are listed in the settings");
        }

        public Skill writeOwn(String userId, String name, String description, String content) {
            throw new UnsupportedOperationException("Skills are listed in the settings");
        }

        public boolean deleteGlobal(String name) {
            return false;
        }

        public boolean deleteOwn(String userId, String name) {
            return false;
        }

        public void deleteAllOwn(String userId) {
        }

        public List<String> ownersOf(String name) {
            return List.of();
        }
    }
}
