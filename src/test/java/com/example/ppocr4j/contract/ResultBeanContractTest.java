package com.example.ppocr4j.contract;

import com.example.ppocr4j.parser.PassportResult;
import net.dreamlu.mica.ai.ppocr.structured.parser.core.BaseStructuredResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门禁 G6：结构化结果类的 Bean 契约。
 *
 * <p><b>为什么这个测试是刚需</b>：本项目没有 lombok，所有自定义 Result 类的
 * getter/setter 都是手写的（{@link PassportResult} 一个类就有 20 个字段、40 个方法）。
 * 漏写一个 getter，Jackson 在 {@code OcrParseService#extractFields} 序列化时会
 * <b>静默丢掉该字段</b>——接口少返回一个值，没有任何编译错误、没有任何运行时异常，
 * 只有调用方在生产上发现字段永远是空的。
 *
 * <p>反射扫一遍就能把这类问题锁死在提交之前。新增证件解析器时，把结果类加进
 * {@link #RESULT_CLASSES} 即可。
 */
class ResultBeanContractTest {

    /** 本项目自建的结构化结果类。新增证件类型时在此登记。 */
    private static final List<Class<? extends BaseStructuredResult>> RESULT_CLASSES = List.of(
            PassportResult.class);

    /** 基类自带、由上游 lombok 生成的字段，不参与本项目的手写契约检查。 */
    private static final Set<String> BASE_FIELDS = Set.of("rawResults", "fieldBoxes");

    @Test
    void everyResultClassHasReadableGetterForEveryField() throws IntrospectionException {
        List<String> violations = new ArrayList<>();

        for (Class<? extends BaseStructuredResult> type : RESULT_CLASSES) {
            Set<String> readable = Arrays.stream(Introspector.getBeanInfo(type).getPropertyDescriptors())
                    .filter(pd -> pd.getReadMethod() != null)
                    .map(PropertyDescriptor::getName)
                    .collect(Collectors.toSet());
            Set<String> writable = Arrays.stream(Introspector.getBeanInfo(type).getPropertyDescriptors())
                    .filter(pd -> pd.getWriteMethod() != null)
                    .map(PropertyDescriptor::getName)
                    .collect(Collectors.toSet());

            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                        || BASE_FIELDS.contains(f.getName())) {
                    continue;
                }
                if (!readable.contains(f.getName())) {
                    violations.add(type.getSimpleName() + "." + f.getName() + " 缺少 getter（Jackson 会静默丢弃该字段）");
                }
                if (!writable.contains(f.getName())) {
                    violations.add(type.getSimpleName() + "." + f.getName() + " 缺少 setter（解析器无法写入该字段）");
                }
            }
        }

        assertThat(violations).as("结构化结果类的 Bean 契约违规").isEmpty();
    }

    /**
     * 结果类必须继承 {@link BaseStructuredResult}，否则拿不到 rawResults / fieldBoxes，
     * 且 {@code OcrParseService} 的注册签名不接受。
     */
    @ParameterizedTest
    @ValueSource(classes = {PassportResult.class})
    void resultClassesExtendBaseStructuredResult(Class<?> type) {
        assertThat(BaseStructuredResult.class).isAssignableFrom(type);
    }

    /**
     * 结果类不允许自己声明 rawResults / fieldBoxes ——
     * 那会遮蔽基类字段，导致 {@code extractFields} 的剔除逻辑失效、响应体重复膨胀。
     */
    @Test
    void resultClassesDoNotShadowBaseFields() {
        List<String> shadowed = new ArrayList<>();
        for (Class<? extends BaseStructuredResult> type : RESULT_CLASSES) {
            for (Field f : type.getDeclaredFields()) {
                if (BASE_FIELDS.contains(f.getName())) {
                    shadowed.add(type.getSimpleName() + "." + f.getName());
                }
            }
        }
        assertThat(shadowed).as("遮蔽了基类字段").isEmpty();
    }
}
