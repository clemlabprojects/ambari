/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.service.gitops;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The repository as a file profile's deploy would leave it: every file, marked with where it comes from and what the
 * deploy does to it. Built from the same plan the deploy writes, against the branch it writes to.
 */
public final class ProfilePreview {

    /** Where a file of the preview comes from. */
    public static final String ORIGIN_CHART = "chart";
    public static final String ORIGIN_FILE = "file";
    public static final String ORIGIN_REPOSITORY = "repository";

    /** What the deploy does to a file. */
    public static final String CHANGE_NEW = "new";
    public static final String CHANGE_CHANGED = "changed";
    public static final String CHANGE_UNCHANGED = "unchanged";
    public static final String CHANGE_REMOVED = "removed";
    /** A {@code createIfMissing} file the repository has: left as it is. */
    public static final String CHANGE_KEPT = "kept";

    /** Longest text shown for a file the deploy writes; longer ones are listed without content. */
    static final int MAX_SHOWN_BYTES = 256 * 1024;
    /** Longest text shown for a file the profile does not manage, and the total shown for all of them. */
    static final int MAX_SHOWN_REPOSITORY_BYTES = 64 * 1024;
    static final int MAX_SHOWN_REPOSITORY_TOTAL = 2 * 1024 * 1024;
    /** Longest file of the repository compared with what the deploy writes; a longer one is taken as changed. */
    static final long MAX_COMPARED_BYTES = 8L * 1024 * 1024;
    /** A file of the repository longer than {@link #MAX_COMPARED_BYTES}: never read. */
    private static final byte[] TOO_LARGE = new byte[0];
    /** Most text a deploy's edits may hold, all files together. */
    public static final int MAX_EDITS_BYTES = 256 * 1024;

    /**
     * One file of the preview.
     *
     * @param origin    {@link #ORIGIN_CHART}, {@link #ORIGIN_FILE} (a rule of the profile) or {@link #ORIGIN_REPOSITORY}
     * @param change    what the deploy does to it
     * @param mode      the rule's write mode, for a file of a rule
     * @param editable  whether the operator may edit it before the deploy: a {@code createIfMissing} file the
     *                  repository does not have yet
     * @param protectedPath whether the profile protects it
     * @param binary    whether it is not text (its content is then not shown)
     * @param content   its text after the deploy, {@code null} when not shown
     * @param current   its text before the deploy, for a changed file
     */
    public record PreviewFile(String path, String origin, String change, String mode, boolean editable,
                              boolean protectedPath, boolean binary, String content, String current) {}

    /**
     * A preview.
     *
     * @param chartPath where the chart is copied, {@code null} when the profile copies none
     * @param notes     what the preview does not show as the deploy will write it
     */
    public record Preview(String profile, String repoUrl, String baseBranch, String chart, String version,
                          String chartPath, List<PreviewFile> files, List<String> notes) {}

    private ProfilePreview() {
    }

    /**
     * The files of the repository as the deploy would leave it.
     *
     * @param chartDir   the chart's path in the repository, {@code null} when the profile copies no chart
     * @param chartFiles the chart's files by path inside the chart
     * @param files      the files the profile's rules render, with the operator's edits applied
     * @param repoDir    the branch the deploy writes to, as checked out; an empty directory for a new repository
     */
    public static List<PreviewFile> files(GitOpsProfile profile, String chartDir, Map<String, byte[]> chartFiles,
                                          List<ProfileRenderer.RenderedFile> files, Path repoDir) throws IOException {
        Map<String, PreviewFile> out = new TreeMap<>();
        if (chartDir != null) {
            for (Map.Entry<String, byte[]> file : chartFiles.entrySet()) {
                String path = chartDir + "/" + file.getKey();
                byte[] current = read(repoDir, path);
                out.put(path, written(path, ORIGIN_CHART, null, false, file.getValue(), current));
            }
        }
        for (ProfileRenderer.RenderedFile file : files) {
            byte[] current = read(repoDir, file.path());
            if (GitOpsProfile.MODE_CREATE_IF_MISSING.equals(file.mode()) && current != null) {
                String text = current == TOO_LARGE ? null : text(current, MAX_SHOWN_BYTES);
                out.put(file.path(), new PreviewFile(file.path(), ORIGIN_FILE, CHANGE_KEPT, file.mode(), false, false,
                        text == null, text, null));
                continue;
            }
            out.put(file.path(), written(file.path(), ORIGIN_FILE, file.mode(),
                    GitOpsProfile.MODE_CREATE_IF_MISSING.equals(file.mode()),
                    file.content().getBytes(StandardCharsets.UTF_8), current));
        }
        // The chart's own entries are replaced by the new version; the other folders of the chart folder stay.
        java.util.Set<String> chartEntries = ProfileRenderer.chartEntries(chartFiles.keySet());
        int shown = 0;
        for (String path : repositoryFiles(repoDir)) {
            if (out.containsKey(path)) {
                continue;
            }
            boolean underChart = ProfileRenderer.chartEntryOf(chartDir, path, chartEntries) != null;
            long size = Files.size(repoDir.resolve(path));
            // Only read when shown: a file longer than that is listed without its content.
            byte[] bytes = underChart || size > MAX_SHOWN_REPOSITORY_BYTES || shown + size > MAX_SHOWN_REPOSITORY_TOTAL
                    ? null : Files.readAllBytes(repoDir.resolve(path));
            String text = bytes == null ? null : text(bytes, MAX_SHOWN_REPOSITORY_BYTES);
            if (text != null) {
                shown += bytes.length;
            }
            out.put(path, new PreviewFile(path, underChart ? ORIGIN_CHART : ORIGIN_REPOSITORY,
                    // The chart is replaced as a whole: a file the new version does not have goes.
                    underChart ? CHANGE_REMOVED : CHANGE_UNCHANGED, null, false,
                    !underChart && ProfileRenderer.isProtected(profile, path), bytes != null && isBinary(bytes), text,
                    null));
        }
        return new ArrayList<>(out.values());
    }

    private static PreviewFile written(String path, String origin, String mode, boolean createIfMissing, byte[] after,
                                       byte[] before) {
        String change = before == null ? CHANGE_NEW
                : before != TOO_LARGE && Arrays.equals(before, after) ? CHANGE_UNCHANGED : CHANGE_CHANGED;
        String text = text(after, MAX_SHOWN_BYTES);
        String previous = CHANGE_CHANGED.equals(change) && before != TOO_LARGE ? text(before, MAX_SHOWN_BYTES) : null;
        return new PreviewFile(path, origin, change, mode, createIfMissing && before == null, false,
                isBinary(after), text, previous);
    }

    /**
     * The rendered files with the operator's edits in place of the rendered content. Only a {@code createIfMissing}
     * file the repository does not have yet may be edited: KDPS never rewrites it afterwards. A file written again by
     * every deploy would lose a one-off edit, and the chart is copied as published.
     *
     * @param edits        path to the edited content; {@code null} or empty for none
     * @param inRepository whether the branch the deploy writes to has a path
     * @throws IllegalArgumentException naming a file that may not be edited
     * @throws IllegalStateException    when an edit holds a secret value of the deploy
     */
    public static List<ProfileRenderer.RenderedFile> withEdits(List<ProfileRenderer.RenderedFile> files,
                                                               Map<String, String> edits,
                                                               Predicate<String> inRepository,
                                                               Collection<String> secretValues) {
        if (edits == null || edits.isEmpty()) {
            return files;
        }
        long size = 0;
        for (String content : edits.values()) {
            size += content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
        }
        if (size > MAX_EDITS_BYTES) {
            throw new IllegalArgumentException("The edited files hold more than " + MAX_EDITS_BYTES / 1024
                    + " KiB. Commit large files to the repository directly.");
        }
        Map<String, ProfileRenderer.RenderedFile> byPath = new LinkedHashMap<>();
        files.forEach(f -> byPath.put(f.path(), f));
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            String path = edit.getKey();
            ProfileRenderer.RenderedFile file = byPath.get(path);
            if (file == null) {
                throw new IllegalArgumentException("No file rule of the profile writes " + path + ": only a file the "
                        + "profile creates can be edited before the deploy.");
            }
            if (!GitOpsProfile.MODE_CREATE_IF_MISSING.equals(file.mode())) {
                throw new IllegalArgumentException(path + " is written again by every deploy, so an edit would be "
                        + "lost: change the wizard setting or the profile template that produces it.");
            }
            if (inRepository.test(path)) {
                throw new IllegalArgumentException(path + " is already in the repository, and KDPS never rewrites "
                        + "it: edit it in Git.");
            }
            String content = edit.getValue() == null ? "" : edit.getValue();
            ProfileRenderer.checkNoSecret(path, content, secretValues);
            byPath.put(path, new ProfileRenderer.RenderedFile(path, content, file.mode()));
        }
        return new ArrayList<>(byPath.values());
    }

    /**
     * The regular files of a checkout, as repository paths, Git's own directory left out. A symbolic link is neither
     * listed nor followed: it could lead to a file outside the repository (KDPS checks links out as plain files).
     */
    private static List<String> repositoryFiles(Path repoDir) throws IOException {
        if (repoDir == null || !Files.isDirectory(repoDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(repoDir)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> repoDir.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals(".git") && !p.startsWith(".git/"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * A file the deploy writes, as the repository holds it: {@code null} when absent, {@link #TOO_LARGE} when longer
     * than what is compared.
     *
     * @throws IllegalArgumentException when the path goes through a symbolic link, which the deploy refuses too
     */
    private static byte[] read(Path repoDir, String path) throws IOException {
        if (repoDir == null) {
            return null;
        }
        Path current = repoDir;
        for (Path part : Path.of(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(path + " goes through the symbolic link " + repoDir.relativize(current)
                        + " of the repository, and KDPS never writes through a link.");
            }
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return Files.size(current) > MAX_COMPARED_BYTES ? TOO_LARGE : Files.readAllBytes(current);
    }

    private static boolean isBinary(byte[] bytes) {
        return utf8(bytes) == null;
    }

    /** The bytes as text when they are UTF-8 without NUL and not longer than {@code max}; otherwise {@code null}. */
    private static String text(byte[] bytes, int max) {
        return bytes.length > max ? null : utf8(bytes);
    }

    private static String utf8(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
