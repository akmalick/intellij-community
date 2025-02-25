/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.branch

import com.intellij.dvcs.branch.BranchType
import git4idea.GitBranch

enum class GitBranchType constructor(private val myName: String) : BranchType {
  LOCAL("LOCAL"), REMOTE("REMOTE");

  override fun getName(): String {
    return myName
  }

  companion object {
    fun of(branch: GitBranch) = if (branch.isRemote) REMOTE else LOCAL
  }
}
