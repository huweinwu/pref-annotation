package gt.tools.preference.annotation.processing;

import gt.tools.preference.annotation.*;
import gt.tools.preference.annotation.processing.visitors.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes({"gt.tools.preference.annotation.PreferenceAnnotation", "gt.tools.preference.annotation.BooleanPreference", "gt.tools.preference.annotation.FloatPreference"})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class PreferenceProcessor extends AbstractProcessor {
    private static final Map<Class<? extends Annotation>, GenVisitor> sVisiters = new HashMap<>();

    static {
        sVisiters.put(BooleanPreference.class, new BooleanVisiter());
        sVisiters.put(FloatPreference.class, new FloatVisitor());
        sVisiters.put(IntPreference.class, new IntVisitor());
        sVisiters.put(LongPreference.class, new LongVisitor());
        sVisiters.put(StringPreference.class, new StringVisitor());
    }

    private static Messager sMessager;
    private Filer mFiler;
    private BufferedReader mConfigReader;
    private String mPkgName = "com";
    private Map<String, PreferenceTemplate> mPrefs;
    private boolean mHasProcessed;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        mFiler = processingEnv.getFiler();
        sMessager = processingEnv.getMessager();
        mPrefs = new HashMap<>();
        try {
            FileObject config = mFiler.getResource(StandardLocation.SOURCE_PATH, "", "pref-annotation-config.conf");
            mConfigReader = new BufferedReader(config.openReader(true));
            int line = 1;
            String pkg = mConfigReader.readLine();
            while (pkg == null || "".equals(pkg.trim())) {
                ++line;
                pkg = mConfigReader.readLine();
                if (!pkg.trim().startsWith("package:")) {
                    pkg = null;
                    break;
                }
            }
            debug(null, "got package name " + pkg);
            if (pkg != null) {
                mPkgName = pkg;
            } else {
                //todo test
                mConfigReader.reset();
                for (int i = 0; i < line; ++i) {
                    mConfigReader.readLine();
                }
            }
        } catch (IOException e1) {
//            e1.printStackTrace();
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (mHasProcessed) {
            return true;
        }
        for (Element rootClass : roundEnv.getElementsAnnotatedWith(PreferenceAnnotation.class)) {
            if (rootClass == null || rootClass.getKind() != ElementKind.CLASS) {
                continue;
            }
            List<? extends Element> elements = rootClass.getEnclosedElements();
            for (Element field : elements) {
                if (field == null || field.getKind() != ElementKind.FIELD) {
                    continue;
                }
                Annotation annotation = null;
                for (Class<? extends Annotation> annotationClass : sVisiters.keySet()) {
                    annotation = field.getAnnotation(annotationClass);
                    if (annotation != null) {
                        break;
                    }
                }
                gen((TypeElement) rootClass, field, annotation);
            }
        }
        try {
            if (mConfigReader != null) {
                String line = mConfigReader.readLine();
                while (line != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 4) {
                        Annotation annotation = null;
                        switch (parts[3]) {
                            case "boolean":
                                annotation = buildBooleanPreference(parts);
                                break;
                            case "float":
                                annotation = buildFloatPreference(parts);
                                break;
                        }
                        gen(null, null, annotation);
                    }
                    line = mConfigReader.readLine();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        writeToFile();
        mHasProcessed = true;
        return true;
    }

    private void gen(TypeElement rootClass, Element field, Annotation annotation) {
        GenVisitor visitor = sVisiters.get(annotation.getClass().getInterfaces()[0]);
        String prefName = visitor.getPrefName(annotation);
        prefName = "".equals(prefName) ? "DefaultPrefHelper" : prefName;
        initPref(prefName);
        mPrefs.get(prefName).accept(visitor, rootClass, field, annotation);
    }

    private void writeToFile() {
        for (String pref : mPrefs.keySet()) {
            try {
                FileObject file = mFiler.createSourceFile(mPkgName + "." + pref);
                Writer writer = file.openWriter();
                writer.write(mPrefs.get(pref).toString(mPkgName));
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private FloatPreference buildFloatPreference(final String[] parts) {
        return new FloatPreference() {

            @Override
            public Class<? extends Annotation> annotationType() {
                return FloatPreference.class;
            }

            @Override
            public String prefName() {
                return parts[0];
            }

            @Override
            public String key() {
                return parts[1];
            }

            @Override
            public float def() {
                return Float.valueOf(parts[2]);
            }
        };

    }

    private BooleanPreference buildBooleanPreference(final String[] parts) {
        return new BooleanPreference() {

            @Override
            public Class<? extends Annotation> annotationType() {
                return BooleanPreference.class;
            }

            @Override
            public String prefName() {
                return parts[0];
            }

            @Override
            public String key() {
                return parts[1];
            }

            @Override
            public boolean def() {
                return Boolean.valueOf(parts[2]);
            }
        };
    }

    private void initPref(String prefName) {
        if (mPrefs.containsKey(prefName)) {
            return;
        }
        StringBuilder whole = new StringBuilder();
        whole.
                append("public final class ").append(prefName).append("{\n").
                append("  private static SharedPreferences sPreferences = (SharedPreferences)ProviderContext.getInstance().get(\"").append(prefName).append("\");\n");
        PreferenceTemplate template = new PreferenceTemplate(whole.toString());
        mPrefs.put(prefName, template);
    }

    public static void debug(Element e, String msg, Object... args) {
        sMessager.printMessage(Diagnostic.Kind.WARNING, String.format(msg, args), e);
    }

    public static void exception(String s) {
        sMessager.printMessage(Diagnostic.Kind.ERROR, s, null);
        throw new IllegalArgumentException(s);
    }
}