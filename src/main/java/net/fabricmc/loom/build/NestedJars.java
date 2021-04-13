/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.transform.StringZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.util.Constants;

public class NestedJars {
	public static boolean addNestedJars(Project project, Path modJarPath) {
		List<File> containedJars = getContainedJars(project);

		if (containedJars.isEmpty()) {
			return false;
		}

		File modJar = modJarPath.toFile();

		ZipUtil.addOrReplaceEntries(modJar, containedJars.stream().map(file -> new FileSource("META-INF/jars/" + file.getName(), file)).toArray(ZipEntrySource[]::new));

		return ZipUtil.transformEntries(modJar, single(new ZipEntryTransformerEntry("fabric.mod.json", new StringZipEntryTransformer() {
			@Override
			protected String transform(ZipEntry zipEntry, String input) {
				JsonObject json = LoomGradlePlugin.GSON.fromJson(input, JsonObject.class);
				JsonArray nestedJars = json.getAsJsonArray("jars");

				if (nestedJars == null || !json.has("jars")) {
					nestedJars = new JsonArray();
				}

				for (File file : containedJars) {
					JsonObject jsonObject = new JsonObject();
					jsonObject.addProperty("file", "META-INF/jars/" + file.getName());
					nestedJars.add(jsonObject);
				}

				json.add("jars", nestedJars);

				return LoomGradlePlugin.GSON.toJson(json);
			}
		})));
	}

	private static List<File> getContainedJars(Project project) {
		List<File> fileList = new ArrayList<>();

		if (project.getExtensions().getByType(LoomGradleExtension.class).isForge()) {
			return fileList;
		}

		Configuration configuration = project.getConfigurations().getByName(Constants.Configurations.INCLUDE);
		ResolvedConfiguration resolvedConfiguration = configuration.getResolvedConfiguration();
		Set<ResolvedDependency> dependencies = resolvedConfiguration.getFirstLevelModuleDependencies();

		// Bit ugly doing this, id guess there is a better way but this works.
		Set<String> projectDeps = new HashSet<>();

		for (Dependency dependency : configuration.getDependencies()) {
			if (dependency instanceof ProjectDependency) {
				ProjectDependency projectDependency = (ProjectDependency) dependency;
				Project dependencyProject = projectDependency.getDependencyProject();

				projectDeps.add(dependency.getGroup() + ":" + dependency.getName() + ":" + dependency.getVersion());

				// TODO change this to allow just normal jar tasks, so a project can have a none loom sub project
				Collection<Task> remapJarTasks = dependencyProject.getTasksByName("remapJar", false);
				Collection<Task> jarTasks = dependencyProject.getTasksByName("jar", false);

				for (Task task : remapJarTasks.isEmpty() ? jarTasks : remapJarTasks) {
					if (task instanceof RemapJarTask) {
						fileList.addAll(prepareForNesting(
								Collections.singleton(((RemapJarTask) task).getArchivePath()),
								projectDependency,
								new ProjectDependencyMetaExtractor(),
								project
						));
					} else if (task instanceof AbstractArchiveTask) {
						fileList.addAll(prepareForNesting(
								Collections.singleton(((AbstractArchiveTask) task).getArchivePath()),
								projectDependency,
								new ProjectDependencyMetaExtractor(),
								project
						));
					}
				}
			}
		}

		for (ResolvedDependency dependency : dependencies) {
			if (projectDeps.contains(dependency.getModuleGroup() + ":" + dependency.getModuleName() + ":" + dependency.getModuleVersion())) {
				continue;
			} else {
				fileList.addAll(prepareForNesting(
						dependency
								.getModuleArtifacts()
								.stream()
								.map(ResolvedArtifact::getFile)
								.collect(Collectors.toSet()),
						dependency,
						new ResolvedDependencyMetaExtractor(),
						project
				));
			}
		}

		for (File file : fileList) {
			if (!file.exists()) {
				throw new RuntimeException("Failed to include nested jars, as it could not be found @ " + file.getAbsolutePath());
			}

			if (file.isDirectory() || !file.getName().endsWith(".jar")) {
				throw new RuntimeException("Failed to include nested jars, as file was not a jar: " + file.getAbsolutePath());
			}
		}

		return fileList;
	}

	// Looks for any deps that require a sub project to be built first
	public static List<RemapJarTask> getRequiredTasks(Project project) {
		List<RemapJarTask> remapTasks = new ArrayList<>();

		Configuration configuration = project.getConfigurations().getByName(Constants.Configurations.INCLUDE);
		DependencySet dependencies = configuration.getDependencies();

		for (Dependency dependency : dependencies) {
			if (dependency instanceof ProjectDependency) {
				ProjectDependency projectDependency = (ProjectDependency) dependency;
				Project dependencyProject = projectDependency.getDependencyProject();

				for (Task task : dependencyProject.getTasksByName("remapJar", false)) {
					if (task instanceof RemapJarTask) {
						remapTasks.add((RemapJarTask) task);
					}
				}
			}
		}

		return remapTasks;
	}

	//This is a good place to do pre-nesting operations, such as adding a fabric.mod.json to a library
	private static <D> List<File> prepareForNesting(Set<File> files, D dependency, DependencyMetaExtractor<D> metaExtractor, Project project) {
		List<File> fileList = new ArrayList<>();

		for (File file : files) {
			//A lib that doesnt have a mod.json, we turn it into a fake mod
			if (!ZipUtil.containsEntry(file, "fabric.mod.json")) {
				LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
				File tempDir = new File(extension.getUserCache(), "temp/modprocessing");

				if (!tempDir.exists()) {
					tempDir.mkdirs();
				}

				File tempFile = new File(tempDir, file.getName());

				if (tempFile.exists()) {
					tempFile.delete();
				}

				try {
					FileUtils.copyFile(file, tempFile);
				} catch (IOException e) {
					throw new RuntimeException("Failed to copy file", e);
				}

				ZipUtil.addEntry(tempFile, "fabric.mod.json", getMod(dependency, metaExtractor).getBytes());
				fileList.add(tempFile);
			} else {
				// Default copy the jar right in
				fileList.add(file);
			}
		}

		return fileList;
	}

	// Generates a barebones mod for a dependency
	private static <D> String getMod(D dependency, DependencyMetaExtractor<D> metaExtractor) {
		JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("schemaVersion", 1);
		jsonObject.addProperty("id", (metaExtractor.group(dependency) + "_" + metaExtractor.name(dependency)).replaceAll("\\.", "_").toLowerCase(Locale.ENGLISH));
		jsonObject.addProperty("version", metaExtractor.version(dependency));
		jsonObject.addProperty("name", metaExtractor.name(dependency));

		JsonObject custom = new JsonObject();
		custom.addProperty("fabric-loom:generated", true);
		jsonObject.add("custom", custom);

		return LoomGradlePlugin.GSON.toJson(jsonObject);
	}

	private static ZipEntryTransformerEntry[] single(ZipEntryTransformerEntry element) {
		return new ZipEntryTransformerEntry[]{element};
	}

	private interface DependencyMetaExtractor<D> {
		String group(D dependency);

		String version(D dependency);

		String name(D dependency);
	}

	private static final class ProjectDependencyMetaExtractor implements DependencyMetaExtractor<ProjectDependency> {
		@Override
		public String group(ProjectDependency dependency) {
			return dependency.getGroup();
		}

		@Override
		public String version(ProjectDependency dependency) {
			return dependency.getVersion();
		}

		@Override
		public String name(ProjectDependency dependency) {
			return dependency.getName();
		}
	}

	private static final class ResolvedDependencyMetaExtractor implements DependencyMetaExtractor<ResolvedDependency> {
		@Override
		public String group(ResolvedDependency dependency) {
			return dependency.getModuleGroup();
		}

		@Override
		public String version(ResolvedDependency dependency) {
			return dependency.getModuleVersion();
		}

		@Override
		public String name(ResolvedDependency dependency) {
			return dependency.getModuleName();
		}
	}
}
