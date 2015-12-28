package com.peterservice.camel.component.restlet;
//! Комментарии в коде помечены восклицательным знаком после //
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.routing.Route;
import org.restlet.routing.Template;
import org.restlet.routing.VirtualHost;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created with IntelliJ IDEA.
 * User: Alexey.Borodin
 * Date: 25.05.15
 * Time: 14:56
 * To change this template use File | Settings | File Templates.
 */
public class VirtualHostWithPSMatching extends VirtualHost {

    private static Pattern pattern = Pattern
            .compile("((?:[a-zA-Z\\d\\-\\.\\_\\~\\!\\$\\&\\'\\(\\)\\*\\+\\,\\;\\=\\:\\@]|(?:\\%[\\dABCDEFabcdef][\\dABCDEFabcdef]))+)");

    private List<RouteSplit<Route, String[]>> splittedRoutes = new LinkedList<RouteSplit<Route, String[]>>();

    private volatile boolean isRoutesUpToDate;

    private static final int CONST_INDEXES_COUNT = 2;

    private static List<Integer> constIndexes = new ArrayList<Integer>(CONST_INDEXES_COUNT);

    // ! для замены строковых литералов
    private static final String SLASH = "/";

    private static final String EMPTY_STRING = "";

    private static final String LEFT_BRACE = "{";

    private static final String LEFT_BRACE_ASCII = "%7B";

    private static final String RIGHT_BRACE = "}";

    private static final String RIGHT_BRACE_ASCII = "%7D";
    static {
        constIndexes.add(0);
        constIndexes.add(0);
    }

    // Вспомогательный класс для хранения пары "Маршрут - Массив частей URL"
    private static class RouteSplit<K, V> implements Map.Entry<K, V> {

        private final K key;

        private V value;

        public RouteSplit(final K key, final V value) {
            this.key = key;
            this.value = value;
        }
        @Override
        public K getKey() {
            return key;
        }
        @Override
        public V getValue() {
            return value;
        }
        @Override
        public V setValue(V value) {
            final V old = this.value;
            this.value = value;
            return old;
        }
    }

    public VirtualHostWithPSMatching(VirtualHost copyFrom) {
        super(copyFrom.getContext(), ".*", ".*", ".*", ".*", ".*", ".*", ".*", ".*");
        init();
        setDefaultMatchingMode(Template.MODE_EQUALS);
        setRoutingMode(MODE_CUSTOM);
    }

    private void init() {
        synchronized (this) {
            splittedRoutes.clear();
            for (final Route current : getRoutes()) {
                if (current.getTemplate() == null || current.getTemplate().getPattern() == null) {
                    continue;
                }
                String tempPattern = current.getTemplate().getPattern();
                // ! проверка tempPattern вынесена отдельно.
                // ! Если бы двойное условие не прошло, а tempPattern == null,
                // ! то tempPattern.split("/") выбросил бы nullPointerException
                if (tempPattern.startsWith(SLASH)) {
                    if (tempPattern.length() > 1) {
                        // ! предпочтительно использовать StringBuilder
                        // ! т.к. строка копируется и остается в памяти
                        tempPattern = tempPattern.substring(1);
                    } else {
                        tempPattern = EMPTY_STRING;
                    }
                }
                splittedRoutes.add(new RouteSplit<Route, String[]>(current, tempPattern.split(SLASH)));
            }
            isRoutesUpToDate = true;
        }
    }

    @Override
    public Route attach(String uriPattern, Restlet target) {
        isRoutesUpToDate = false;
        return super.attach(uriPattern, target);
    }

    @Override
    public void detach(Restlet target) {
        isRoutesUpToDate = false;
        super.detach(target);
    }

    /**
     * original version
     * deprecated due to optimizations
     * left only for performance tests
     * 
     * @deprecated (deprecated due to optimizations)
     *             Не менялось и не использовалось
     */
    @Deprecated
    public Route getOriginalCustom(Request request) {
        float score;
        LinkedList<Route> matchingRoutes = new LinkedList<Route>();
        for (final Route current : getRoutes()) {
            score = score(current, request);
            // ! Вещественные числа не должны сравниваться напрямую
            // ! Вместо этого используют отхождение от Eps = 0.001
            if (score >= 1.0F) {
                matchingRoutes.add(current);
            }
        }
        if (matchingRoutes.isEmpty()) {
            return null;
        }
        Route bestRoute = matchingRoutes.get(0);
        for (Route r : matchingRoutes) {
            if (r.equals(bestRoute)) {
                continue;
            }
            if (r.getTemplate() != null && r.getTemplate().getPattern() != null
                    && secondUrlMoreSpecific(bestRoute.getTemplate().getPattern(), r.getTemplate().getPattern())) {
                bestRoute = r;
            }
        }
        return bestRoute;
    }

    private Object getBestMatch(Matcher[] matchers, String[] splittedRequest) {
        Object bestMatch = null;
        // Для хранения позиций переменных url лучшего совпадения bestMatch
        List<Integer> varIndexes = constIndexes;
        for (final RouteSplit<Route, String[]> current : splittedRoutes) {
            // ! убрано приведение типов, типы добавлены явно
            // Получаем список индексов переменных или null, если совпадения нет
            final List<Integer> result = match(current.getValue(), splittedRequest, matchers);
            if (result == null) {
                continue;
            }
            final ListIterator<Integer> varIndIterator = varIndexes.listIterator();
            final ListIterator<Integer> resultIterator = result.listIterator();
            while (varIndIterator.hasNext() && resultIterator.hasNext()) {
                final int varIndValue = varIndIterator.next();
                final int resultValue = resultIterator.next();
                if (varIndValue != resultValue) {
                    // У current переменная url появляется позже, значить current более специфичен, чем bestMatch
                    if (varIndValue < resultValue) {
                        bestMatch = current.getKey();
                        varIndexes = result;
                    }
                    break;
                }
            }
            // current более специфичен, т.к. содержит меньше переменных
            if (!resultIterator.hasNext() && varIndIterator.hasNext()) {
                bestMatch = current.getKey();
                varIndexes = result;
            }
        }
        return bestMatch;
    }

    @Override
    // Если маршруты были изменены, обновляем
    public Route getCustom(Request request, Response response) {
        if (!isRoutesUpToDate) {
            init();
        }
        // Получаем матчеры и разделенную строку
        RouteSplit<Matcher[], String[]> matcherAndSplittedRequest = requestParse(request);
        if (matcherAndSplittedRequest == null) {
            return null;
        }
        // ! убрано приведение типов, типы добавлены явно
        final Matcher[] matchers = matcherAndSplittedRequest.getKey();
        // ! убрано приведение типов, типы добавлены явно
        final String[] splittedRequest = matcherAndSplittedRequest.getValue();
        return (Route) getBestMatch(matchers, splittedRequest);
    }

    protected RouteSplit<Matcher[], String[]> requestParse(Request request) {
        if (request.getResourceRef() != null) {
            // Получаем url из запроса
            String remainingPart = request.getResourceRef().getRemainingPart(false, false);
            if (remainingPart != null && !remainingPart.isEmpty()) {
                if (remainingPart.startsWith(SLASH)) {
                    if (remainingPart.length() > 1) {
                        // ! предпочтительно использовать StringBuilder
                        remainingPart = remainingPart.substring(1);
                    } else {
                        remainingPart = EMPTY_STRING;
                    }
                }
                // ! магическая константа
                final String[] splittedRequest = remainingPart.split(SLASH);
                // Для каждой части url создаем матчер
                Matcher[] matchers = new Matcher[splittedRequest.length];
                for (int i = 0; i < splittedRequest.length; i++) {
                    matchers[i] = pattern.matcher(splittedRequest[i]);
                }
                return new RouteSplit<Matcher[], String[]>(matchers, splittedRequest);
            }
        }
        return null;
    }

    /**
     * @deprecated (Не менялось и не использовалось)
     */
    @Deprecated
    private static String getUrlCommonPrefix(String url1, String url2) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < url1.length(); i++) {
            if (i >= url2.length()) {
                return sb.toString();
            }
            if (url1.charAt(i) == url2.charAt(i)) {
                sb.append(url1.charAt(i));
            } else {
                return sb.toString();
            }
        }
        return sb.toString();
    }

    /**
     * @deprecated (Не менялось и не использовалось)
     */
    @Deprecated
    private static boolean secondUrlMoreSpecific(String url1, String url2) {
        String url1WithoutSlash = url1;
        String url2WithoutSlash = url2;
        if (url1.startsWith(SLASH)) {
            url1WithoutSlash = url1.substring(1);
        }
        if (url2.startsWith(SLASH)) {
            url2WithoutSlash = url2.substring(1);
        }
        final String commonPart = getUrlCommonPrefix(url1WithoutSlash, url2WithoutSlash);
        // don't swap if there's nothing in common with two functions
        if (!commonPart.isEmpty()) {
            // if url1 starts with url2 then url1 is more specific
            if (commonPart.length() == url2WithoutSlash.length()) {
                return false;
            }

            // common prefix but different second parts
            char url1WithoutSlashNext = url1WithoutSlash.charAt(commonPart.length());
            // if url2 starts with url1 then url2 is more specific or
            // url1 is less specific => url2 is more specific
            final char leftBrace = '{';

            return commonPart.length() == url1WithoutSlash.length() || url1WithoutSlashNext == leftBrace;
        }
        return false;
    }

    /**
     * @deprecated (Не менялось и не использовалось)
     */
    @Deprecated
    protected float score(Route current, Request request) {
        float result = 0F;
        if (request.getResourceRef() != null && current.getTemplate() != null) {
            final String remainingPart = request.getResourceRef().getRemainingPart(false, current.isMatchingQuery());
            if (remainingPart != null) {
                final int matchedLength = current.getTemplate().match(remainingPart);
                if (matchedLength != -1) {
                    final float totalLength = remainingPart.length();
                    if (totalLength > 0.0F) {
                        result = getRequiredScore() + (1.0F - getRequiredScore()) * (matchedLength / totalLength);
                        if (current.getTemplate().getPattern().equals(remainingPart)) {
                            result += 0.1F;
                        }
                    } else {
                        result = 1.0F;
                    }
                }
            }
            if (getLogger().isLoggable(Level.FINER)) {
                getLogger().finer(
                        "Call score for the \"" + current.getTemplate().getPattern() + "\" URI pattern: " + result);
            }
        }
        return result;
    }

    protected List<Integer> match(String[] splittedTemplate, String[] splittedRequest, Matcher[] matchers) {
        // Если кол-во частей в url разное, то запрос под шаблон не подходит
        if (splittedTemplate.length != splittedRequest.length) {
            return null;
        }
        // Храним номера частей url, в которых содержатся переменные
        List<Integer> varIndexes = new LinkedList<Integer>();
        for (int i = 0; i < splittedTemplate.length; i++) {
            if (!splittedTemplate[i].equals(splittedRequest[i])) {
                // ! Заменен прямой доступ к символу. splittedTemplate изначально может содержать пустые строки
                // ! Строковые константы заменены именованными
                // В случае несовпадения проверяем, является ли эта часть переменной
                if (!splittedTemplate[i].startsWith(LEFT_BRACE) || !splittedTemplate[i].endsWith(RIGHT_BRACE)) {
                    return null;
                }
                // Чтобы не декодировать remainingPart в score3 ищем { и } в их ASCII виде
                // ! Новые переменные для удобочитаемости
                boolean requestItemHasLeftBrace = splittedRequest[i].contains(LEFT_BRACE_ASCII);
                boolean requestItemHasRightBrace = splittedRequest[i].contains(RIGHT_BRACE_ASCII);
                boolean requestItemEmpty = splittedRequest[i].isEmpty();
                if (!requestItemEmpty && (!matchers[i].matches() || requestItemHasLeftBrace || requestItemHasRightBrace)) {
                    return null;
                }
                varIndexes.add(i);
            }
        }
        return varIndexes;
    }
}
