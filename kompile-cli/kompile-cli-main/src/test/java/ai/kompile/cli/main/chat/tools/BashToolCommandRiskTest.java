/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolCommandRiskTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "git diff --check",
            "git status --short",
            "git --no-pager log --oneline -5",
            "git -C . show HEAD",
            "git branch --list 'feature/*'",
            "git tag --list 'v*'",
            "git remote -v",
            "git stash list",
            "git config --get user.name",
            "git reflog show",
            "git diff --check && git status --short",
            "git log --oneline | head -5"
    })
    void recognizedGitInspectionsAreReadOnly(String command) {
        assertTrue(BashTool.isReadOnlyCommand(command), command);
        assertTrue(BashTool.isReadOnlyGitCommand(command), command);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "git revert HEAD",
            "git log\nrm -rf build-cache",
            "git show HEAD && rm obsolete.txt",
            "git commit -m test",
            "git reset --soft HEAD~1",
            "git branch new-branch",
            "git branch -m renamed",
            "git branch --list -d old-branch",
            "git tag v1.0.0",
            "git tag --list -d v1.0.0",
            "git remote add origin https://example.invalid/repo.git",
            "git remote -v add origin https://example.invalid/repo.git",
            "git remote show origin",
            "git stash push",
            "git config user.name Example",
            "git config --get user.name --unset user.name",
            "git reflog expire --all",
            "git diff --output=review.patch",
            "git diff --ext-diff",
            "git -c alias.inspect=commit inspect",
            "git diff --check && git commit -m test"
    })
    void mutatingOrExtensibleGitFormsDoNotReceiveReadOnlyClassification(String command) {
        assertFalse(BashTool.isReadOnlyCommand(command), command);
        assertFalse(BashTool.isReadOnlyGitCommand(command), command);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "python3 -c mutate && git diff --check",
            "git diff --check | python3 -c consume",
            "git diff --check; pwd",
            "git diff $(python3 -c mutate)",
            "git diff --check &"
    })
    void compoundShellBehaviorDoesNotInheritTheGitFastPath(String command) {
        assertFalse(BashTool.isReadOnlyGitCommand(command), command);
    }

    @Test
    void commandPermissionsDistinguishInspectionMutationAndDeletion() {
        org.junit.jupiter.api.Assertions.assertEquals("bash.readonly", BashTool.commandPermissionKey("git show HEAD"));
        org.junit.jupiter.api.Assertions.assertEquals("bash.write", BashTool.commandPermissionKey("git revert HEAD"));
        org.junit.jupiter.api.Assertions.assertEquals("bash.destructive", BashTool.commandPermissionKey("rm -rf build-cache"));
        org.junit.jupiter.api.Assertions.assertEquals("bash.destructive", BashTool.commandPermissionKey("unlink obsolete"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"git push origin HEAD -f", "git push --force-with-lease origin HEAD",
            "git -C . push origin +HEAD:main", "git reset HEAD --hard", "git clean -xdf target",
            "git branch --force --delete old", "git branch -fd old", "git branch old -D"})
    void destructiveGitPatternsIgnoreFlagOrder(String command) {
        org.junit.jupiter.api.Assertions.assertEquals("bash.destructive", BashTool.commandPermissionKey(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {"git push origin force-topic", "git reset --hardly", "git clean --dry-run",
            "git branch --force new", "git push origin --forceful"})
    void destructivePatternsRequireWholeOptionTokens(String command) {
        org.junit.jupiter.api.Assertions.assertEquals("bash.write", BashTool.commandPermissionKey(command));
    }

    @Test
    void mentioningGitWithoutInvokingItDoesNotReceiveGitFastPath() {
        assertTrue(BashTool.isReadOnlyCommand("echo git diff"));
        assertFalse(BashTool.isReadOnlyGitCommand("echo git diff"));
    }
}
