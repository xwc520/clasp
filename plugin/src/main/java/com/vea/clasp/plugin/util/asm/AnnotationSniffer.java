package com.vea.clasp.plugin.util.asm;

import com.vea.clasp.plugin.util.Util;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

public class AnnotationSniffer extends ClassVisitor {

    private Set<String> annotations = new HashSet<>();

    public AnnotationSniffer(ClassVisitor next) {
        super(Opcodes.ASM5, next);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        onAnnotationFound(descriptor, visible);
        return super.visitAnnotation(descriptor, visible);
    }

    protected void onAnnotationFound(String descriptor, boolean visible) {
        annotations.add(Util.objDescToInternalName(descriptor));
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        return new FieldVisitor(Opcodes.ASM5, super.visitField(access, name, descriptor, signature, value)) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                onAnnotationFound(descriptor, visible);
                return super.visitAnnotation(descriptor, visible);
            }
        };
    }

    public Set<String> annotations() {
        return annotations;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, descriptor, signature, exceptions)) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                onAnnotationFound(descriptor, visible);
                return super.visitAnnotation(descriptor, visible);
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                onAnnotationFound(descriptor, visible);
                return super.visitParameterAnnotation(parameter, descriptor, visible);
            }
        };
    }
}
