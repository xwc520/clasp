package com.vea.clasp.plugin.process.visitors;

import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInvocation;
import com.vea.clasp.plugin.api.asm.CaptClassVisitor;
import com.vea.clasp.plugin.api.asm.ClassVisitorManager;
import com.vea.clasp.plugin.api.transform.ClassRequest;
import com.vea.clasp.plugin.api.transform.ClassTransformer;
import com.vea.clasp.plugin.api.transform.TransformContext;
import com.vea.clasp.plugin.graph.ApkClassGraph;
import com.vea.clasp.plugin.graph.ApkClassInfo;
import com.vea.clasp.plugin.resource.GlobalResource;
import com.vea.clasp.plugin.resource.VariantResource;
import com.vea.clasp.plugin.util.ClassWalker;
import com.vea.clasp.plugin.util.Util;
import com.vea.clasp.plugin.util.WaitableTasks;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

public class ThirdRound {
    private static final Logger LOGGER = Logging.getLogger(ThirdRound.class);
    private final VariantResource variantResource;
    private final GlobalResource global;
    private final ApkClassGraph graph;
    private boolean hasAll = false;
    private final ClassVisitorManager manager = new ClassVisitorManager();

    public ThirdRound(VariantResource variantResource, GlobalResource global, ApkClassGraph graph) {
        this.variantResource = variantResource;
        this.global = global;
        this.graph = graph;
    }

    public void accept(boolean incremental, ClassWalker walker, TransformProviderFactory manager, TransformInvocation invocation)
            throws IOException, InterruptedException, TransformException {
        List<PluginTransform> transforms = manager.create().map(PluginTransform::new).collect(Collectors.toList());

        // 1. call beforeTransform to collect class request
        ForkJoinPool pool = global.computation();
        final WaitableTasks computation = WaitableTasks.get(pool);
        transforms.forEach(c -> computation.execute(c::doRequest));
        computation.await();

        // 2. dispatch classes
        walker.visit(hasAll, incremental, true, asFactory(transforms));

        if (incremental) {
            // dispatch REMOVED
            graph.getAll().values()
                    .stream()
                    .filter(c -> c.status() == com.vea.clasp.plugin.api.graph.Status.REMOVED)
                    .forEach(c -> transformForRemoved(pool, transforms, c));
        }
        if (!hasAll && incremental) {
            // dispatch extraSpecified() & re rack for removed plugins
            // directory is simple, jar may cause rewrite the whole jar, take care of it
            Future<Map<QualifiedContent, Set<String>>> future = computation.submit(() -> {
                Map<String, QualifiedContent> inputs = invocation.getInputs().stream()
                        .flatMap(i -> Stream.concat(i.getDirectoryInputs().stream(), i.getJarInputs().stream()))
                        .collect(Collectors.toMap(QualifiedContent::getName, Function.identity()));

                return Stream.concat(
                        manager.collectRemovedPluginsAffectedClasses(graph),
                        transforms.stream()
                                .flatMap(t -> t.extra.stream())
                                .map(graph::get)
                                .filter(Objects::nonNull)
                                .filter(c -> c.status() == com.vea.clasp.plugin.api.graph.Status.NOT_CHANGED))
                        // ignore to remove
                        .collect(Collectors.groupingBy(s -> inputs.get(s.clazz.belongsTo), HashMap::new,
                                Collector.of(HashSet::new, (s, v) -> s.add(v.name()), (s1, s2) -> {
                                    s1.addAll(s2);
                                    return s1;
                                }, Collector.Characteristics.UNORDERED)));
            });
            walker.visitTargets(asFactory(transforms), Util.await(future));
        } else {
            pool.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }

        // 3. call afterTransform
        WaitableTasks io = WaitableTasks.get(global.io());
        transforms.forEach(t -> io.submit(() -> {
            t.provider.transformer().afterTransform();
            return null;
        }));
        io.await();
    }

    private static void transformForRemoved(ForkJoinPool pool, List<PluginTransform> transforms, ApkClassInfo info) {
        pool.execute(new RecursiveAction() {
            @Override
            protected void compute() {
                invokeAll(transforms.stream().<RecursiveAction>map(c -> new RecursiveAction() {
                    @Override
                    protected void compute() {
                        // just ignore result, because removed class can't be transformed
                        c.createVisitor(info);
                    }
                }).toArray(ForkJoinTask[]::new));
            }
        });
    }

    private ClassWalker.Visitor.Factory asFactory(List<PluginTransform> transforms) {
        return (incremental, content) -> new TransformVisitor(transforms);
    }


    class TransformVisitor implements ClassWalker.Visitor {

        private final List<PluginTransform> transforms;

        TransformVisitor(List<PluginTransform> transforms) {
            this.transforms = transforms;
        }

        @Nullable
        @Override
        public ForkJoinTask<ClassWalker.ClassEntry> onVisit(ForkJoinPool pool, @Nullable byte[] classBytes, String className, Status status) {
            if (status != Status.REMOVED) {
                return pool.submit(() -> {
                    // retain null to keep the original order map to transform
                    List<CaptClassVisitor> visitors = transforms
                            .stream()
                            .map(t -> t.createVisitor(graph.get(className)))
                            .collect(Collectors.toList());
                    final int flags = visitors.stream().filter(Objects::nonNull).flatMap(manager::expand)
                            .map(manager::beforeAttach)
                            .reduce(0, (l, r) -> l | r);
                    ClassReader cr = new ClassReader(classBytes);

                    int writeFlag = (flags >> 16) & 0xff;
                    ClassWriter cw = ((writeFlag & ClassWriter.COMPUTE_FRAMES) != 0)
                            ? new ComputeFrameClassWriter(cr, writeFlag, variantResource.getFullAndroidLoader())
                            : new ClassWriter(cr, writeFlag);

                    ClassVisitor header = cw;
                    List<CaptClassVisitor> preGroup = null;
                    // link every two expanded group
                    for (int i = 0; i < visitors.size(); i++) {
                        CaptClassVisitor v = visitors.get(i);
                        if (v != null) {
                            TransformContext context = transforms.get(i).makeContext(className, cw);
                            List<CaptClassVisitor> curGroup = manager.expand(v).peek(s -> manager.attach(s, context)).collect(Collectors.toList());
                            if (preGroup == null) {
                                header = curGroup.get(0);
                            } else {
                                manager.link(preGroup.get(preGroup.size() - 1), curGroup.get(0));
                            }
                            preGroup = curGroup;
                        }
                    }

                    // the last group link cw
                    if (preGroup != null) {
                        manager.link(preGroup.get(preGroup.size() - 1), cw);
                    }

                    try {
                        cr.accept(header, flags & 0xff);
                    } catch (RuntimeException e) {
                        LOGGER.warn("Transform class '" + className + "' failed, skip it", e);
                        return new ClassWalker.ClassEntry(className, classBytes);
                    } finally {
                        if (header instanceof CaptClassVisitor) {
                            manager.expand((CaptClassVisitor) header)
                                    .forEach(manager::detach);
                        }
                    }
                    return new ClassWalker.ClassEntry(className, cw.toByteArray());
                });
            }
            return null;
        }
    }

    class PluginTransform {
        final TransformProvider provider;
        ClassRequest request;
        Set<String> extra;

        PluginTransform(TransformProvider provider) {
            this.provider = provider;
        }

        void doRequest() {
            this.request = Objects.requireNonNull(provider.transformer().beforeTransform());
            extra = Objects.requireNonNull(request.extraSpecified());
            if (!hasAll && Objects.requireNonNull(request.scope()) == ClassRequest.Scope.ALL) {
                hasAll = true;
            }
        }

        CaptClassVisitor createVisitor(ApkClassInfo info) {
            boolean target = isTarget(info);
            // we skip the plugin that scope == NONE
            if (!target && request.scope() == ClassRequest.Scope.NONE) {
                return null;
            }
            return provider.transformer().onTransform(info, target);
        }

        boolean isTarget(ApkClassInfo info) {
            switch (request.scope()) {
                case ALL:
                    return true;
                case CHANGED:
                    if (info.status() != com.vea.clasp.plugin.api.graph.Status.NOT_CHANGED) {
                        return true;
                    }
                default:
                    return extra.contains(info.name());
            }
        }

        TransformContext makeContext(String className, ClassWriter writer) {
            return new TransformContext() {

                @Override
                public void notifyChanged() {
                    provider.onClassAffected(className);
                }

                @Override
                public ClassVisitor getLastWriter() {
                    return writer;
                }
            };
        }
    }

    static class ComputeFrameClassWriter extends ClassWriter {

        private final URLClassLoader classLoader;

        ComputeFrameClassWriter(int flags, URLClassLoader classLoader) {
            super(flags);
            this.classLoader = classLoader;
        }

        ComputeFrameClassWriter(ClassReader classReader, int flags, URLClassLoader classLoader) {
            super(classReader, flags);
            this.classLoader = classLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            Class<?> c;
            Class<?> d;
            ClassLoader classLoader = this.classLoader;
            try {
                c = Class.forName(type1.replace('/', '.'), false, classLoader);
                d = Class.forName(type2.replace('/', '.'), false, classLoader);
            } catch (Exception e) {
                throw new RuntimeException(
                        String.format(
                                "Unable to find common supper type for %s and %s.", type1, type2),
                        e);
            }
            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            } else {
                do {
                    c = c.getSuperclass();
                } while (!c.isAssignableFrom(d));
                return c.getName().replace('.', '/');
            }
        }
    }


    public interface TransformProvider {

        void onClassAffected(String className);

        ClassTransformer transformer();

    }

    public interface TransformProviderFactory {

        Stream<TransformProvider> create();

        // Rerack classes for removed plugin
        Stream<ApkClassInfo> collectRemovedPluginsAffectedClasses(ApkClassGraph graph);
    }
}
