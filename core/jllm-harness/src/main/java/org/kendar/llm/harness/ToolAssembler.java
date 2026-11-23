package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.permission.PermissionRules;
import org.kendar.llm.harness.permission.ToolApprovalPolicy;
import org.kendar.llm.harness.safety.ContentWarnings;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.skills.SkillCatalog;
import org.kendar.llm.harness.skills.SkillTool;
import org.kendar.llm.harness.tools.*;

import java.util.ArrayList;
import java.util.List;

/** Builds the tool registry: the standard tools, the injected and plugin ones, the skill tool and the hiding rules. */
final class ToolAssembler {

    record Tools(HarnessToolRegistry registry, List<HarnessTool> fileTools, Checkpoints checkpoints,
                 StorageQuota quota, SkillCatalog skills, PermissionRules permissionRules) {
    }

    private ToolAssembler() {
    }

    static Tools assemble(HarnessSettings settings, HarnessConfig config, ObjectMapper mapper, SandboxExecutor box,
                          boolean standardTools, PluginInstaller plugins, ToolApprovalPolicy approvalPolicy,
                          ContentWarnings contentWarnings, boolean multiUser) {
        Checkpoints checkpoints = new Checkpoints();
        List<HarnessTool> all = new ArrayList<>();
        List<HarnessTool> fileTools = new ArrayList<>();
        StorageQuota quota = new StorageQuota(settings.storageMaxBytes(), box::chatStorage);
        if (standardTools) {
            fileTools.addAll(new FileSystemAgentTools(plugins.documentReaders, checkpoints, quota,
                    settings.filesMaxReadBytes()).tools());
            all.addAll(fileTools);
            all.addAll(new ShellAgentTools(box, config).tools());
            all.addAll(new WebAgentTools(config, mapper, quota, settings.webFetchMaxBytes()).tools());
        }
        all.removeIf(t -> plugins.removedTools.contains(t.name()));
        all.addAll(plugins.tools);
        SkillCatalog skills = new SkillCatalog(settings.skills(), contentWarnings);
        // registered even without skills: a user policy may bring the user's own (dropped per chat when none)
        if (!skills.isEmpty() || multiUser) all.add(new SkillTool(skills));
        HarnessToolRegistry registry = new HarnessToolRegistry(all, approvalPolicy, plugins.replacedTools,
                plugins.nameChecks);
        PermissionRules permissionRules = settings.permissionRules();
        registry.hideIf(permissionRules::hides);
        return new Tools(registry, fileTools, checkpoints, quota, skills, permissionRules);
    }
}
