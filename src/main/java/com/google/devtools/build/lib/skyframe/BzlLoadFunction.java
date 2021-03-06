// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;


import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.InconsistentFilesystemException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.BazelModuleContext;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.StarlarkExportable;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.LoadStatement;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.StarlarkFile;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.Statement;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.RecordingSkyFunctionEnvironment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A Skyframe function to look up and load a single .bzl module.
 *
 * <p>Given a {@link Label} referencing a .bzl file, attempts to locate the file and load it. The
 * Label must be absolute, and must not reference the special {@code external} package. If loading
 * is successful, returns a {@link BzlLoadValue} that encapsulates the loaded {@link Module} and its
 * transitive digest and {@link StarlarkFileDependency} information. If loading is unsuccessful,
 * throws a {@link BzlLoadFunctionException} that encapsulates the cause of the failure.
 */
public class BzlLoadFunction implements SkyFunction {

  // Creates the BazelStarlarkContext and populates the predeclared .bzl symbols.
  private final RuleClassProvider ruleClassProvider;
  // Only used to retrieve the "native" object.
  private final PackageFactory packageFactory;

  private final ASTFileLookupValueManager astFileLookupValueManager;
  @Nullable private final SelfInliningManager selfInliningManager;

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private BzlLoadFunction(
      RuleClassProvider ruleClassProvider,
      PackageFactory packageFactory,
      ASTFileLookupValueManager astFileLookupValueManager,
      @Nullable SelfInliningManager selfInliningManager) {
    this.ruleClassProvider = ruleClassProvider;
    this.packageFactory = packageFactory;
    this.astFileLookupValueManager = astFileLookupValueManager;
    this.selfInliningManager = selfInliningManager;
  }

  public static BzlLoadFunction create(
      RuleClassProvider ruleClassProvider,
      PackageFactory packageFactory,
      DigestHashFunction digestHashFunction,
      Cache<Label, ASTFileLookupValue> astFileLookupValueCache) {
    return new BzlLoadFunction(
        ruleClassProvider,
        packageFactory,
        // When we are not inlining BzlLoadValue nodes, there is no need to have separate
        // ASTFileLookupValue nodes for bzl files. Instead we inline them for a strict memory win,
        // at a small code complexity cost.
        //
        // Detailed explanation:
        // (1) The ASTFileLookupValue node for a bzl file is used only for the computation of
        // that file's BzlLoadValue node. So there's no concern about duplicate work that would
        // otherwise get deduped by Skyframe.
        // (2) ASTFileLookupValue doesn't have an interesting equality relation, so we have no
        // hope of getting any interesting change-pruning of ASTFileLookupValue nodes. If we
        // had an interesting equality relation that was e.g. able to ignore benign
        // whitespace, then there would be a hypothetical benefit to having separate
        // ASTFileLookupValue nodes (e.g. on incremental builds we'd be able to not re-execute
        // top-level code in bzl files if the file were reparsed to an equivalent AST).
        // (3) A ASTFileLookupValue node lets us avoid redoing work on a BzlLoadFunction Skyframe
        // restart, but we can also achieve that result ourselves with a cache that persists between
        // Skyframe restarts.
        //
        // Therefore, ASTFileLookupValue nodes are wasteful from two perspectives:
        // (a) ASTFileLookupValue contains a StarlarkFile, and that business object is really
        // just a temporary thing for bzl execution. Retaining it forever is pure waste.
        // (b) The memory overhead of the extra Skyframe node and edge per bzl file is pure
        // waste.
        new InliningAndCachingASTFileLookupValueManager(
            ruleClassProvider, digestHashFunction, astFileLookupValueCache),
        /*selfInliningManager=*/ null);
  }

  public static BzlLoadFunction createForInliningSelfForPackageAndWorkspaceNodes(
      RuleClassProvider ruleClassProvider,
      PackageFactory packageFactory,
      int bzlLoadValueCacheSize) {
    return new BzlLoadFunction(
        ruleClassProvider,
        packageFactory,
        // When we are inlining BzlLoadValue nodes, then we want to have explicit ASTFileLookupValue
        // nodes, since now (1) in the comment above doesn't hold. This way we read and parse each
        // needed bzl file at most once total globally, rather than once per need (in the worst-case
        // of a BzlLoadValue inlining cache miss). This is important in the situation where a bzl
        // file is loaded by a lot of other bzl files or BUILD files.
        RegularSkyframeASTFileLookupValueManager.INSTANCE,
        new SelfInliningManager(bzlLoadValueCacheSize));
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    BzlLoadValue.Key key = (BzlLoadValue.Key) skyKey.argument();
    try {
      return computeInternal(key, env, /*inliningState=*/ null);
    } catch (InconsistentFilesystemException e) {
      throw new BzlLoadFunctionException(e, Transience.PERSISTENT);
    } catch (BzlLoadFailedException e) {
      throw new BzlLoadFunctionException(e);
    }
  }

  @Nullable
  BzlLoadValue computeWithSelfInlineCallsForPackageAndWorkspaceNodes(
      BzlLoadValue.Key key,
      Environment env,
      Map<BzlLoadValue.Key, CachedBzlLoadValueAndDeps> visitedDepsInToplevelLoad)
      throws InconsistentFilesystemException, BzlLoadFailedException, InterruptedException {
    Preconditions.checkNotNull(selfInliningManager);
    // See comments in computeWithSelfInlineCallsInternal for an explanation of the visitedNested
    // and visitedDepsInToplevelLoad vars.
    CachedBzlLoadValueAndDeps cachedBzlLoadValueAndDeps =
        computeWithSelfInlineCallsInternal(
            key,
            env,
            // visitedNested must use insertion order to display the correct error.
            /*visitedNested=*/ new LinkedHashSet<>(),
            /*visitedDepsInToplevelLoad=*/ visitedDepsInToplevelLoad);
    if (cachedBzlLoadValueAndDeps == null) {
      return null;
    }
    return cachedBzlLoadValueAndDeps.getValue();
  }

  @Nullable
  private CachedBzlLoadValueAndDeps computeWithSelfInlineCallsInternal(
      BzlLoadValue.Key key,
      Environment env,
      Set<BzlLoadValue.Key> visitedNested,
      Map<BzlLoadValue.Key, CachedBzlLoadValueAndDeps> visitedDepsInToplevelLoad)
      throws InconsistentFilesystemException, BzlLoadFailedException, InterruptedException {
    // Under BzlLoadFunction inlining, BUILD and WORKSPACE files are evaluated in separate Skyframe
    // threads, but all the .bzls transitively loaded by a single package occur in one thread. All
    // these threads share a global cache in selfInliningManager, so that once any thread completes
    // evaluation of a .bzl, it needn't be evaluated again (unless it's evicted).
    //
    // If two threads race to evaluate the same .bzl, each one will see a different copy of it, and
    // only one will end up in the global cache. This presents a hazard if the same BUILD or
    // WORKSPACE file has a diamond dependency on foo.bzl, evaluates it the first time, and gets a
    // different copy of it from the cache the second time. This is because Starlark values may use
    // object identity, which breaks the moment two distinct observable copies are visible in the
    // same context (see b/138598337).
    //
    // (Note that blocking evaluation of .bzls on retrievals from the global cache doesn't work --
    // two threads could deadlock while trying to evaluate an illegal load() cycle from opposite
    // ends.)
    //
    // To solve this, we keep a second cache in visitedDepsInToplevelLoad, of just the .bzls
    // transitively loaded in the current package. The entry for foo.bzl may be a different copy
    // than the one in the global cache, but the BUILD or WORKSPACE file won't know the difference.
    // (We don't need to worry about Starlark values from different packages interacting since
    // inlining is only used for the loading phase.)
    //
    CachedBzlLoadValueAndDeps cachedBzlLoadValueAndDeps = visitedDepsInToplevelLoad.get(key);
    if (cachedBzlLoadValueAndDeps == null) {
      cachedBzlLoadValueAndDeps = selfInliningManager.bzlLoadValueCache.getIfPresent(key);
      if (cachedBzlLoadValueAndDeps != null) {
        cachedBzlLoadValueAndDeps.traverse(env::registerDependencies, visitedDepsInToplevelLoad);
      }
    }
    if (cachedBzlLoadValueAndDeps != null) {
      return cachedBzlLoadValueAndDeps;
    }

    // visitedNested is keyed on the SkyKey, not the label, because it's possible for distinct keys
    // to share the same label. Examples include the "@builtins" pseudo-repo vs a real repository
    // that happens to be named "@builtins", or keys for the same .bzl with different workspace
    // chunking information. It's unclear whether these particular cycles can arise in practice, but
    // it doesn't hurt to be robust to future changes that may make that possible.
    if (!visitedNested.add(key)) {
      ImmutableList<BzlLoadValue.Key> cycle =
          CycleUtils.splitIntoPathAndChain(Predicates.equalTo(key), visitedNested).second;
      throw new BzlLoadFailedException("Starlark load cycle: " + cycle);
    }

    CachedBzlLoadValueAndDeps.Builder inlineCachedValueBuilder =
        selfInliningManager.cachedBzlLoadValueAndDepsBuilderFactory
            .newCachedBzlLoadValueAndDepsBuilder();
    // Use an instrumented Skyframe env to capture Skyframe deps in the CachedBzlLoadValueAndDeps.
    // This is transitive but doesn't include deps underneath recursively loaded .bzls (the
    // recursion uses the unwrapped original env).
    Preconditions.checkState(
        !(env instanceof RecordingSkyFunctionEnvironment),
        "Found nested RecordingSkyFunctionEnvironment but it should have been stripped: %s",
        env);
    RecordingSkyFunctionEnvironment recordingEnv =
        new RecordingSkyFunctionEnvironment(
            env,
            inlineCachedValueBuilder::addDep,
            inlineCachedValueBuilder::addDeps,
            inlineCachedValueBuilder::noteException);
    BzlLoadValue value =
        computeInternal(
            key,
            recordingEnv,
            new InliningState(visitedNested, inlineCachedValueBuilder, visitedDepsInToplevelLoad));
    // All loads traversed, this key can no longer be part of a cycle.
    Preconditions.checkState(visitedNested.remove(key), key);

    if (value != null) {
      inlineCachedValueBuilder.setValue(value);
      inlineCachedValueBuilder.setKey(key);
      cachedBzlLoadValueAndDeps = inlineCachedValueBuilder.build();
      visitedDepsInToplevelLoad.put(key, cachedBzlLoadValueAndDeps);
      selfInliningManager.bzlLoadValueCache.put(key, cachedBzlLoadValueAndDeps);
    }
    return cachedBzlLoadValueAndDeps;
  }

  public void resetSelfInliningCache() {
    selfInliningManager.reset();
  }

  private static ContainingPackageLookupValue getContainingPackageLookupValue(
      Environment env, Label label)
      throws InconsistentFilesystemException, BzlLoadFailedException, InterruptedException {
    PathFragment dir = Label.getContainingDirectory(label);
    PackageIdentifier dirId =
        PackageIdentifier.create(label.getPackageIdentifier().getRepository(), dir);
    ContainingPackageLookupValue containingPackageLookupValue;
    try {
      containingPackageLookupValue =
          (ContainingPackageLookupValue)
              env.getValueOrThrow(
                  ContainingPackageLookupValue.key(dirId),
                  BuildFileNotFoundException.class,
                  InconsistentFilesystemException.class);
    } catch (BuildFileNotFoundException e) {
      throw BzlLoadFailedException.errorReadingFile(
          label.toPathFragment(), new ErrorReadingStarlarkExtensionException(e));
    }
    if (containingPackageLookupValue == null) {
      return null;
    }
    // Ensure the label doesn't cross package boundaries.
    if (!containingPackageLookupValue.hasContainingPackage()) {
      throw BzlLoadFailedException.noBuildFile(
          label, containingPackageLookupValue.getReasonForNoContainingPackage());
    }
    if (!containingPackageLookupValue
        .getContainingPackageName()
        .equals(label.getPackageIdentifier())) {
      throw BzlLoadFailedException.labelCrossesPackageBoundary(label, containingPackageLookupValue);
    }
    return containingPackageLookupValue;
  }

  private static class InliningState {
    private final Set<BzlLoadValue.Key> visitedNested;
    private final CachedBzlLoadValueAndDeps.Builder inlineCachedValueBuilder;
    private final Map<BzlLoadValue.Key, CachedBzlLoadValueAndDeps> visitedDepsInToplevelLoad;

    private InliningState(
        Set<BzlLoadValue.Key> visitedNested,
        CachedBzlLoadValueAndDeps.Builder inlineCachedValueBuilder,
        Map<BzlLoadValue.Key, CachedBzlLoadValueAndDeps> visitedDepsInToplevelLoad) {
      this.visitedNested = visitedNested;
      this.inlineCachedValueBuilder = inlineCachedValueBuilder;
      this.visitedDepsInToplevelLoad = visitedDepsInToplevelLoad;
    }
  }

  // It is vital that we don't return any value if any call to env#getValue(s)OrThrow throws an
  // exception. We are allowed to wrap the thrown exception and rethrow it for any calling functions
  // to handle though.
  @Nullable
  private BzlLoadValue computeInternal(
      BzlLoadValue.Key key, Environment env, @Nullable InliningState inliningState)
      throws InconsistentFilesystemException, BzlLoadFailedException, InterruptedException {
    Label label = key.getLabel();
    PathFragment filePath = label.toPathFragment();

    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    if (getContainingPackageLookupValue(env, label) == null) {
      return null;
    }

    // Load the AST corresponding to this file.
    ASTFileLookupValue astLookupValue;
    try {
      astLookupValue = astFileLookupValueManager.getASTFileLookupValue(label, env);
    } catch (ErrorReadingStarlarkExtensionException e) {
      throw BzlLoadFailedException.errorReadingFile(filePath, e);
    }
    if (astLookupValue == null) {
      return null;
    }

    BzlLoadValue result = null;
    try {
      result =
          computeInternalWithAst(
              key, filePath, starlarkSemantics, astLookupValue, env, inliningState);
    } catch (InconsistentFilesystemException | BzlLoadFailedException | InterruptedException e) {
      astFileLookupValueManager.doneWithASTFileLookupValue(label);
      throw e;
    }
    if (result != null) {
      // Result is final (no Skyframe restart), so no further need for the AST value.
      astFileLookupValueManager.doneWithASTFileLookupValue(label);
    }
    return result;
  }

  @Nullable
  private BzlLoadValue computeInternalWithAst(
      BzlLoadValue.Key key,
      PathFragment filePath,
      StarlarkSemantics starlarkSemantics,
      ASTFileLookupValue astLookupValue,
      Environment env,
      @Nullable InliningState inliningState)
      throws InconsistentFilesystemException, BzlLoadFailedException, InterruptedException {
    Label label = key.getLabel();

    if (!astLookupValue.lookupSuccessful()) {
      // Starlark code must exist.
      throw new BzlLoadFailedException(astLookupValue.getError());
    }
    StarlarkFile file = astLookupValue.getAST();
    if (!file.ok()) {
      throw BzlLoadFailedException.skylarkErrors(filePath);
    }

    // Process the load statements in the file,
    // resolving labels relative to the current repo mapping.
    ImmutableMap<RepositoryName, RepositoryName> repoMapping = getRepositoryMapping(key, env);
    if (repoMapping == null) {
      return null;
    }
    List<Pair<String, Label>> loads =
        getLoadLabels(env.getListener(), file, label.getPackageIdentifier(), repoMapping);
    if (loads == null) {
      // malformed load statements
      throw BzlLoadFailedException.skylarkErrors(filePath);
    }

    // Compute Skyframe key for each label in 'loads'.
    List<BzlLoadValue.Key> loadKeys = Lists.newArrayListWithExpectedSize(loads.size());
    for (Pair<String, Label> load : loads) {
      loadKeys.add(key.getKeyForLoad(load.second));
    }

    // Load .bzl modules in parallel.
    List<BzlLoadValue> bzlLoads =
        inliningState == null
            ? computeBzlLoadsNoInlining(env, loadKeys, file.getStartLocation())
            : computeBzlLoadsWithSelfInlining(env, loadKeys, label, inliningState);
    if (bzlLoads == null) {
      return null; // Skyframe deps unavailable
    }

    // Process the loaded modules.
    //
    // Compute a digest of the file itself plus the transitive hashes of the modules it directly
    // loads. Loop iteration order matches the source order of load statements.
    Fingerprint fp = new Fingerprint();
    fp.addBytes(astLookupValue.getDigest());
    Map<String, Module> loadedModules = Maps.newHashMapWithExpectedSize(loads.size());
    ImmutableList.Builder<StarlarkFileDependency> fileDependencies =
        ImmutableList.builderWithExpectedSize(loads.size());
    for (int i = 0; i < loads.size(); i++) {
      String loadString = loads.get(i).first;
      BzlLoadValue v = bzlLoads.get(i);
      loadedModules.put(loadString, v.getModule());
      fileDependencies.add(v.getDependency());
      fp.addBytes(v.getTransitiveDigest());
    }
    byte[] transitiveDigest = fp.digestAndReset();

    // executeModule does not request values from the Environment. It may post events to the
    // Environment, but events do not matter when caching BzlLoadValues.
    Module module =
        executeModule(
            file,
            key.getLabel(),
            transitiveDigest,
            loadedModules,
            starlarkSemantics,
            env,
            /*inWorkspace=*/ key instanceof BzlLoadValue.KeyForWorkspace,
            repoMapping);
    BzlLoadValue result =
        new BzlLoadValue(
            module, transitiveDigest, new StarlarkFileDependency(label, fileDependencies.build()));
    return result;
  }

  private static ImmutableMap<RepositoryName, RepositoryName> getRepositoryMapping(
      BzlLoadValue.Key key, Environment env) throws InterruptedException {
    Label enclosingFileLabel = key.getLabel();

    ImmutableMap<RepositoryName, RepositoryName> repositoryMapping;
    if (key instanceof BzlLoadValue.KeyForWorkspace) {
      // Still during workspace file evaluation
      BzlLoadValue.KeyForWorkspace keyForWorkspace = (BzlLoadValue.KeyForWorkspace) key;
      if (keyForWorkspace.getWorkspaceChunk() == 0) {
        // There is no previous workspace chunk
        repositoryMapping = ImmutableMap.of();
      } else {
        SkyKey workspaceFileKey =
            WorkspaceFileValue.key(
                keyForWorkspace.getWorkspacePath(), keyForWorkspace.getWorkspaceChunk() - 1);
        WorkspaceFileValue workspaceFileValue = (WorkspaceFileValue) env.getValue(workspaceFileKey);
        // Note: we know for sure that the requested WorkspaceFileValue is fully computed so we do
        // not need to check if it is null
        repositoryMapping =
            workspaceFileValue
                .getRepositoryMapping()
                .getOrDefault(
                    enclosingFileLabel.getPackageIdentifier().getRepository(), ImmutableMap.of());
      }
    } else {
      // We are fully done with workspace evaluation so we should get the mappings from the
      // final RepositoryMappingValue
      PackageIdentifier packageIdentifier = enclosingFileLabel.getPackageIdentifier();
      RepositoryMappingValue repositoryMappingValue =
          (RepositoryMappingValue)
              env.getValue(RepositoryMappingValue.key(packageIdentifier.getRepository()));
      if (repositoryMappingValue == null) {
        return null;
      }
      repositoryMapping = repositoryMappingValue.getRepositoryMapping();
    }
    return repositoryMapping;
  }

  /**
   * Returns a list of pairs mapping each load string in the BUILD or .bzl file to the Label it
   * resolves to. Labels are resolved relative to {@code base}, the file's package. If any load
   * statement is malformed, the function reports one or more errors to the handler and returns
   * null. Order matches the source.
   */
  @Nullable
  static List<Pair<String, Label>> getLoadLabels(
      EventHandler handler,
      StarlarkFile file,
      PackageIdentifier base,
      ImmutableMap<RepositoryName, RepositoryName> repoMapping) {
    Preconditions.checkArgument(!base.getRepository().isDefault());

    // It's redundant that getRelativeWithRemapping needs a Label;
    // a PackageIdentifier should suffice. Make one here.
    Label buildLabel = getBUILDLabel(base);

    boolean ok = true;
    List<Pair<String, Label>> loads = Lists.newArrayList();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof LoadStatement) {
        LoadStatement load = (LoadStatement) stmt;
        String module = load.getImport().getValue();

        // Parse the load statement's module string as a label.
        // It must end in .bzl and not be in package "//external".
        try {
          Label label = buildLabel.getRelativeWithRemapping(module, repoMapping);
          if (!label.getName().endsWith(".bzl")) {
            throw new LabelSyntaxException("The label must reference a file with extension '.bzl'");
          }
          if (label.getPackageIdentifier().equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
            throw new LabelSyntaxException(
                "Starlark files may not be loaded from the //external package");
          }
          loads.add(Pair.of(module, label));
        } catch (LabelSyntaxException ex) {
          handler.handle(
              Event.error(
                  load.getImport().getStartLocation(), "in load statement: " + ex.getMessage()));
          ok = false;
        }
      }
    }
    return ok ? loads : null;
  }

  private static Label getBUILDLabel(PackageIdentifier pkgid) {
    try {
      return Label.create(pkgid, "BUILD");
    } catch (LabelSyntaxException e) {
      // Shouldn't happen; the Label is well-formed by construction.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Compute the BzlLoadValue for all given keys using vanilla Skyframe evaluation, returning {@code
   * null} if Skyframe deps were missing and have been requested.
   */
  @Nullable
  private static List<BzlLoadValue> computeBzlLoadsNoInlining(
      Environment env, List<BzlLoadValue.Key> keys, Location locationForErrors)
      throws BzlLoadFailedException, InterruptedException {
    List<BzlLoadValue> bzlLoads = Lists.newArrayListWithExpectedSize(keys.size());
    Map<SkyKey, ValueOrException<BzlLoadFailedException>> values =
        env.getValuesOrThrow(keys, BzlLoadFailedException.class);
    // Uses same order as load()s in the file. Order matters since we report the first error.
    for (BzlLoadValue.Key key : keys) {
      try {
        bzlLoads.add((BzlLoadValue) values.get(key).get());
      } catch (BzlLoadFailedException exn) {
        throw new BzlLoadFailedException(
            "in " + locationForErrors.file() + ": " + exn.getMessage());
      }
    }
    return env.valuesMissing() ? null : bzlLoads;
  }

  /**
   * Compute the BzlLoadValue for all given keys by reusing this instance of the BzlLoadFunction,
   * bypassing traditional Skyframe evaluation, returning {@code null} if Skyframe deps were missing
   * and have been requested.
   */
  @Nullable
  private List<BzlLoadValue> computeBzlLoadsWithSelfInlining(
      Environment env, List<BzlLoadValue.Key> keys, Label fileLabel, InliningState inliningState)
      throws InterruptedException, BzlLoadFailedException, InconsistentFilesystemException {
    Preconditions.checkState(
        env instanceof RecordingSkyFunctionEnvironment,
        "Expected to be recording dep requests when inlining BzlLoadFunction: %s",
        fileLabel);
    Environment strippedEnv = ((RecordingSkyFunctionEnvironment) env).getDelegate();
    List<BzlLoadValue> bzlLoads = Lists.newArrayListWithExpectedSize(keys.size());
    Exception deferredException = null;
    boolean valuesMissing = false;
    // NOTE: Iterating over loads in the order listed in the file.
    for (BzlLoadValue.Key key : keys) {
      CachedBzlLoadValueAndDeps cachedValue;
      try {
        cachedValue =
            computeWithSelfInlineCallsInternal(
                key,
                strippedEnv,
                inliningState.visitedNested,
                inliningState.visitedDepsInToplevelLoad);
      } catch (BzlLoadFailedException | InconsistentFilesystemException e) {
        // For determinism's sake while inlining, preserve the first exception and continue to run
        // subsequently listed loads to completion/exception, loading all transitive deps anyway.
        deferredException = MoreObjects.firstNonNull(deferredException, e);
        continue;
      }
      if (cachedValue == null) {
        Preconditions.checkState(env.valuesMissing(), "no starlark load value for %s", key);
        // We continue making inline calls even if some requested values are missing, to maximize
        // the number of dependent (non-inlined) SkyFunctions that are requested, thus avoiding a
        // quadratic number of restarts.
        valuesMissing = true;
      } else {
        bzlLoads.add(cachedValue.getValue());
        inliningState.inlineCachedValueBuilder.addTransitiveDeps(cachedValue);
      }
    }
    if (deferredException != null) {
      Throwables.throwIfInstanceOf(deferredException, BzlLoadFailedException.class);
      Throwables.throwIfInstanceOf(deferredException, InconsistentFilesystemException.class);
      throw new IllegalStateException(
          "caught a checked exception of unexpected type", deferredException);
    }
    return valuesMissing ? null : bzlLoads;
  }

  /** Executes the .bzl file defining the module to be loaded. */
  private Module executeModule(
      StarlarkFile file,
      Label label,
      byte[] transitiveDigest,
      Map<String, Module> loadedModules,
      StarlarkSemantics starlarkSemantics,
      Environment env,
      boolean inWorkspace,
      ImmutableMap<RepositoryName, RepositoryName> repositoryMapping)
      throws BzlLoadFailedException, InterruptedException {
    // set up .bzl predeclared environment
    Map<String, Object> predeclared = new HashMap<>(ruleClassProvider.getEnvironment());
    predeclared.put("native", packageFactory.getNativeModule(inWorkspace));
    Module module = Module.withPredeclared(starlarkSemantics, predeclared);
    module.setClientData(BazelModuleContext.create(label, transitiveDigest));

    try (Mutability mu = Mutability.create("loading", label)) {
      StarlarkThread thread = new StarlarkThread(mu, starlarkSemantics);
      thread.setLoader(loadedModules::get);
      StoredEventHandler eventHandler = new StoredEventHandler();
      thread.setPrintHandler(Event.makeDebugPrintHandler(eventHandler));
      ruleClassProvider.setStarlarkThreadContext(thread, label, repositoryMapping);
      execAndExport(file, label, eventHandler, module, thread);

      Event.replayEventsOn(env.getListener(), eventHandler.getEvents());
      for (Postable post : eventHandler.getPosts()) {
        env.getListener().post(post);
      }
      if (eventHandler.hasErrors()) {
        throw BzlLoadFailedException.errors(label.toPathFragment());
      }
      return module;
    }
  }

  // Precondition: file is validated and error-free.
  // Precondition: thread has a valid transitiveDigest.
  // TODO(adonovan): executeModule would make a better public API than this function.
  public static void execAndExport(
      StarlarkFile file, Label label, EventHandler handler, Module module, StarlarkThread thread)
      throws InterruptedException {

    // Intercept execution after every assignment at top level
    // and "export" any newly assigned exportable globals.
    // TODO(adonovan): change the semantics; see b/65374671.
    thread.setPostAssignHook(
        (name, value) -> {
          if (value instanceof StarlarkExportable) {
            StarlarkExportable exp = (StarlarkExportable) value;
            if (!exp.isExported()) {
              try {
                exp.export(label, name);
              } catch (EvalException ex) {
                handler.handle(Event.error(ex.getLocation(), ex.getMessage()));
              }
            }
          }
        });

    try {
      EvalUtils.exec(file, module, thread);
    } catch (EvalException ex) {
      handler.handle(Event.error(ex.getLocation(), ex.getMessage()));
    }
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  static final class BzlLoadFailedException extends Exception implements SaneAnalysisException {
    private final Transience transience;

    private BzlLoadFailedException(String errorMessage) {
      super(errorMessage);
      this.transience = Transience.PERSISTENT;
    }

    private BzlLoadFailedException(String errorMessage, Exception cause, Transience transience) {
      super(errorMessage, cause);
      this.transience = transience;
    }

    static BzlLoadFailedException errors(PathFragment file) {
      return new BzlLoadFailedException(String.format("Extension file '%s' has errors", file));
    }

    static BzlLoadFailedException errorReadingFile(
        PathFragment file, ErrorReadingStarlarkExtensionException cause) {
      return new BzlLoadFailedException(
          String.format(
              "Encountered error while reading extension file '%s': %s", file, cause.getMessage()),
          cause,
          cause.getTransience());
    }

    static BzlLoadFailedException noBuildFile(Label file, @Nullable String reason) {
      if (reason != null) {
        return new BzlLoadFailedException(
            String.format("Unable to find package for %s: %s.", file, reason));
      }
      return new BzlLoadFailedException(
          String.format(
              "Every .bzl file must have a corresponding package, but '%s' does not have one."
                  + " Please create a BUILD file in the same or any parent directory. Note that"
                  + " this BUILD file does not need to do anything except exist.",
              file));
    }

    static BzlLoadFailedException labelCrossesPackageBoundary(
        Label label, ContainingPackageLookupValue containingPackageLookupValue) {
      return new BzlLoadFailedException(
          ContainingPackageLookupValue.getErrorMessageForLabelCrossingPackageBoundary(
              // We don't actually know the proper Root to pass in here (since we don't e.g. know
              // the root of the bzl/BUILD file that is trying to load 'label'). Therefore we just
              // pass in the Root of the containing package in order to still get a useful error
              // message for the user.
              containingPackageLookupValue.getContainingPackageRoot(),
              label,
              containingPackageLookupValue));
    }

    static BzlLoadFailedException skylarkErrors(PathFragment file) {
      return new BzlLoadFailedException(String.format("Extension '%s' has errors", file));
    }
  }

  private interface ASTFileLookupValueManager {
    @Nullable
    ASTFileLookupValue getASTFileLookupValue(Label label, Environment env)
        throws InconsistentFilesystemException, InterruptedException,
            ErrorReadingStarlarkExtensionException;

    void doneWithASTFileLookupValue(Label label);
  }

  private static class RegularSkyframeASTFileLookupValueManager
      implements ASTFileLookupValueManager {
    private static final RegularSkyframeASTFileLookupValueManager INSTANCE =
        new RegularSkyframeASTFileLookupValueManager();

    @Nullable
    @Override
    public ASTFileLookupValue getASTFileLookupValue(Label label, Environment env)
        throws InconsistentFilesystemException, InterruptedException,
            ErrorReadingStarlarkExtensionException {
      return (ASTFileLookupValue)
          env.getValueOrThrow(
              ASTFileLookupValue.key(label),
              ErrorReadingStarlarkExtensionException.class,
              InconsistentFilesystemException.class);
    }

    @Override
    public void doneWithASTFileLookupValue(Label label) {}
  }

  private static class InliningAndCachingASTFileLookupValueManager
      implements ASTFileLookupValueManager {
    private final RuleClassProvider ruleClassProvider;
    private final DigestHashFunction digestHashFunction;
    // We keep a cache of ASTFileLookupValues that have been computed but whose corresponding
    // BzlLoadValue has not yet completed. This avoids repeating the ASTFileLookupValue work in case
    // of Skyframe restarts. (If we weren't inlining, Skyframe would cache this for us.)
    private final Cache<Label, ASTFileLookupValue> astFileLookupValueCache;

    private InliningAndCachingASTFileLookupValueManager(
        RuleClassProvider ruleClassProvider,
        DigestHashFunction digestHashFunction,
        Cache<Label, ASTFileLookupValue> astFileLookupValueCache) {
      this.ruleClassProvider = ruleClassProvider;
      this.digestHashFunction = digestHashFunction;
      this.astFileLookupValueCache = astFileLookupValueCache;
    }

    @Nullable
    @Override
    public ASTFileLookupValue getASTFileLookupValue(Label label, Environment env)
        throws InconsistentFilesystemException, InterruptedException,
            ErrorReadingStarlarkExtensionException {
      ASTFileLookupValue value = astFileLookupValueCache.getIfPresent(label);
      if (value == null) {
        value =
            ASTFileLookupFunction.computeInline(
                ASTFileLookupValue.key(label), env, ruleClassProvider, digestHashFunction);
        if (value != null) {
          astFileLookupValueCache.put(label, value);
        }
      }
      return value;
    }

    @Override
    public void doneWithASTFileLookupValue(Label label) {
      astFileLookupValueCache.invalidate(label);
    }
  }

  private static class SelfInliningManager {
    private final int bzlLoadValueCacheSize;
    private Cache<BzlLoadValue.Key, CachedBzlLoadValueAndDeps> bzlLoadValueCache;
    private CachedBzlLoadValueAndDepsBuilderFactory cachedBzlLoadValueAndDepsBuilderFactory =
        new CachedBzlLoadValueAndDepsBuilderFactory();

    private SelfInliningManager(int bzlLoadValueCacheSize) {
      this.bzlLoadValueCacheSize = bzlLoadValueCacheSize;
    }

    private void reset() {
      if (bzlLoadValueCache != null) {
        logger.atInfo().log(
            "Starlark inlining cache stats from earlier build: " + bzlLoadValueCache.stats());
      }
      cachedBzlLoadValueAndDepsBuilderFactory = new CachedBzlLoadValueAndDepsBuilderFactory();
      Preconditions.checkState(
          bzlLoadValueCacheSize >= 0,
          "Expected positive Starlark cache size if caching. %s",
          bzlLoadValueCacheSize);
      bzlLoadValueCache =
          CacheBuilder.newBuilder()
              .concurrencyLevel(BlazeInterners.concurrencyLevel())
              .maximumSize(bzlLoadValueCacheSize)
              .recordStats()
              .build();
    }
  }

  private static final class BzlLoadFunctionException extends SkyFunctionException {
    private BzlLoadFunctionException(BzlLoadFailedException cause) {
      super(cause, cause.transience);
    }

    private BzlLoadFunctionException(InconsistentFilesystemException e, Transience transience) {
      super(e, transience);
    }
  }
}
