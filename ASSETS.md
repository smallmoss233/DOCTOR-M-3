# 美术资产贡献规范 / Asset Contribution Guidelines

本规范适用于本项目收录的所有美术资产（模型、贴图、音效、字体等）。
This document defines the policy for all art assets (models, textures,
sounds, fonts, etc.) included in this project.

---

## 一、著作权 / 1. Copyright

1. 资产著作权归创作者所有。本规范不改变、不转让、不共享这一权利。
   Asset copyright remains with the original creator. This policy does
   not alter, transfer, or share that ownership.

2. 创作者可随时要求停止收录其资产，项目方应配合移除相关文件、
   引用与署名记录。
   The creator may request removal of their assets at any time. The
   project will remove the relevant files, references, and credits.

3. 收录到本项目的资产视为授权项目在项目范围内使用、分发、呈现。
   该授权不扩展到项目之外。
   Assets accepted into the project are licensed for use, distribution,
   and display within the project. This license does not extend beyond
   the project.

---

## 二、署名 / 2. Attribution

项目通过以下方式记录创作者贡献：
The project credits contributors through the following channels:

1. `CREDITS.md` 文件——列出所有资产贡献者及其贡献内容。
   `CREDITS.md` — lists all asset contributors and their contributions.

2. 模型 / 资产文件的 metadata 字段——记录 `author` 或 `contributor`。
   Asset file metadata — records the `author` or `contributor` field.

3. 项目 README、下载页、更新日志中提及。
   Project README, download page, and changelog.

**署名不通过以下方式实现**：
**Attribution is NOT implemented through:**

- 资产本体上的水印、签名、logo
- Watermarks, signatures, or logos on the asset itself
- 玩家正常游玩过程中持续可见的个人标识
- Personal identifiers continuously visible during normal gameplay

---

## 三、默认资产 / 3. Canonical Assets

默认资产（canonical）是玩家默认加载、代表项目整体形象的资产。
Canonical assets are loaded by default and represent the project's
public identity.

**约束 / Requirements:**

1. 不得包含个人水印、logo、签名或其他非项目内容的装饰性标识。
   MUST NOT contain personal watermarks, logos, signatures, or other
   decorative identifiers unrelated to the project.

2. 不得包含与其所声称的主题不符的元素。
   例如：一个"12 任博士 TARDIS"外观不应带有作者署名标识——
   12 任博士的 TARDIS 上没有这个元素。
   MUST NOT contain elements inconsistent with its stated subject.
   Example: a "12th Doctor TARDIS" appearance must not carry author
   identifiers — the 12th Doctor's TARDIS does not have them.

3. 项目方保留对默认资产最终视觉呈现的决定权。
   The project maintains final say over the visual presentation of
   canonical assets.

---

## 四、变体资产 / 4. Variant Assets

不符合第三节规范的资产，可作为**变体（variant）**收录。
Assets that do not meet Section 3 may be included as **variants**.

**变体的性质 / Nature of variants:**

1. 玩家需主动选择才加载，默认不启用。
   Loaded only by explicit player selection. Never enabled by default.

2. 在视觉上、命名上与默认资产明确区分。
   Visually and nominally distinguished from canonical assets.

3. 变体允许保留创作者的个人标识。
   Variants MAY retain the creator's personal identifiers.

---

## 五、提交流程 / 5. Submission

1. 提交资产时，同时提供以下信息：
   When submitting, provide:
    - 创作者署名 / Creator name
    - 资产用途（默认 / 变体）/ Intended use (canonical / variant)
    - 授权说明 / License statement

2. 项目方审查后，决定收录为默认资产、变体资产、或不予收录。
   The project reviews and decides: canonical, variant, or rejected.

3. 未通过审查的资产，项目方需向创作者说明原因。
   For rejected assets, the project MUST provide a reason to the creator.

---

## 六、违规处理 / 6. Violations

若已收录的默认资产被发现有个人标识：
If an included canonical asset is found to contain personal identifiers:

1. 首次：通知创作者，请求修改或转为变体。
   First occurrence: notify the creator, request modification or
   re-classification as a variant.

2. 创作者拒绝修改：从默认资产移除，转为变体或下架。
   If declined: remove from canonical, re-classify as variant or
   remove entirely.

3. 同一创作者重复违规：暂停其资产提交权限，直到沟通完成。
   Repeated violations: suspend the creator's submission privileges
   pending resolution.

---

## 七、本规范的适用范围 / 7. Scope

本规范适用于本项目所有收录的美术资产。
This policy applies to all art assets included in this project.

代码部分遵循项目 LICENSE（MIT）。本规范不覆盖代码。
Code is governed by the project LICENSE (MIT). This document does
not cover code.

---

## 附：为什么需要这份规范 / Appendix: Why This Policy Exists

在个人作品中，创作者可以自由决定署名方式。
In personal works, creators may choose their attribution style freely.

在团队项目中，默认资产代表的是**整体形象**，而不是任何单一个体。
In team projects, canonical assets represent the **collective identity**,
not any single individual.

署名权保护的是"我被记录为贡献者"。
Attribution protects the right to be recorded as a contributor.

水印实现的是"每次你看到它都记得我"。
Watermarks implement "you remember me every time you see it".

本规范保护的，是项目整体的视觉一致性，以及每位贡献者被公
正记录的权利。
This policy protects the project's visual consistency and every
contributor's right to be fairly recorded.

两者不冲突。署名在文档中，项目在现场。
The two do not conflict. Attribution lives in documentation;
the project lives on screen.