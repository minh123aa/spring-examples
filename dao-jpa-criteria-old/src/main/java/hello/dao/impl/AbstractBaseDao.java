package hello.dao.impl;

import hello.container.FieldHolder;
import hello.container.OrderType;
import hello.container.QueryParams;
import hello.dao.BaseDao;
import hello.util.ReflectionUtils;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.springframework.util.Assert;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toSet;
import static org.springframework.util.ObjectUtils.isEmpty;

public abstract class AbstractBaseDao<T, ID extends Serializable> implements BaseDao<T, ID> {

    public final static String FIELD_DELIMITER = ".";

    protected abstract Class<T> getPersistentClass();

    @PersistenceContext
    private EntityManager em;

    protected Session getSession() {
        return em.unwrap(Session.class);
    }

    protected Criteria createEntityCriteria() {
        return getSession()
                .createCriteria(getPersistentClass())
                .setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
    }

    @Override
    public List<T> getAll() {
        return createEntityCriteria().list();
    }

    @Override
    public Optional<T> getById(ID id) {
        T entity = getSession().get(getPersistentClass(), id);
        return Optional.ofNullable(entity);
    }

    @Override
    public void persist(T entity) {
        getSession().persist(entity);
    }

    @Override
    public void saveOrUpdate(T entity) {
        getSession().saveOrUpdate(entity);
    }

    @Override
    public void delete(T entity) {
        getSession().delete(entity);
    }

    @Override
    public List<T> getByProps(Map<String, List<?>> props) {
        Criteria criteria = getCriteriaByProps(props);

        return criteria.list();
    }

    protected Criteria getCriteriaByProps(Map<String, List<?>> props) {
        Objects.requireNonNull(props, "Param 'props' cannot be null, sorry");

        Criteria criteria = createEntityCriteria();
        props.forEach((fieldName, values) -> createCriteriaByFieldNameAndValues(criteria, fieldName, values));
        return criteria;
    }

    protected void createCriteriaByFieldNameAndValues(Criteria criteria, String fieldName, List<?> values) {
        if (!isSimpleField(fieldName)) {
            if (values.size() > 1)
                throw new RuntimeException(String.format("For relation field '%s' must be only one value, for create join query", fieldName));

            getCriteriaAliasRelationIdWithCast(criteria, getFieldNameFromRelation(fieldName), values.get(0));
            return;
        }

        Class<?> fieldType = getFieldType(fieldName);

        if (values.isEmpty()) return;

        if (fieldType.isAssignableFrom(Double.class))
            criteria.add(Restrictions.in(fieldName, values.stream()
                    .map(x -> {
                        if (x instanceof String) {
                            return Double.valueOf((String) x);
                        }
                        return ((Number)x).doubleValue();
                    })
                    .collect(toSet())));

        else if (fieldType.isAssignableFrom(Long.class))
            criteria.add(Restrictions.in(fieldName, values.stream()
                    .map(x -> {
                        if (x instanceof String) {
                            return Long.valueOf((String) x);
                        }
                        return ((Number)x).longValue();
                    })
                    .collect(toSet())));

        else if (fieldType.isAssignableFrom(Float.class))
            criteria.add(Restrictions.in(fieldName, values.stream()
                    .map(x -> {
                        if (x instanceof String) {
                            return Float.valueOf((String) x);
                        }
                        return ((Number)x).floatValue();
                    })
                    .collect(toSet())));

        else if (fieldType.isAssignableFrom(Integer.class))
            criteria.add(Restrictions.in(fieldName, values.stream()
                    .map(x -> {
                        if (x instanceof String) {
                            return Integer.valueOf((String) x);
                        }
                        return ((Number)x).intValue();
                    })
                    .collect(toSet())));

        else if (fieldType.isAssignableFrom(Short.class))
            criteria.add(Restrictions.in(fieldName, values.stream()
                    .map(x -> {
                        if (x instanceof String) {
                            return Short.valueOf((String) x);
                        }
                        return ((Number)x).shortValue();
                    })
                    .collect(toSet())));

        else
            criteria.add(Restrictions.in(fieldName, values));
    }

    private boolean isSimpleField(String fieldName) {
        List<String> el = Arrays.asList(fieldName.split("\\" + FIELD_DELIMITER));
        return el.size() == 1;
    }

    private String getFieldNameFromRelation(String fieldName) {
        List<String> el = Arrays.asList(fieldName.split("\\" + FIELD_DELIMITER));
        return el.get(0);
    }

    private Class<?> getFieldType(String fieldName) {
        Field field = ReflectionUtils.getField(getPersistentClass(), fieldName)
                .orElseThrow(() -> new RuntimeException(String.format("Cannot find fieldName: '%s'", fieldName)));
        return field.getType();
    }

    @Override
    public List<T> getByFields(Collection<FieldHolder> fieldHolders) {
        Objects.requireNonNull(fieldHolders, "Param 'fieldHolders' cannot be null, sorry");

        if (isEmpty(fieldHolders)) {
            return emptyList();
        }

        Criteria criteria = createEntityCriteria();

        for (FieldHolder fieldHolder : fieldHolders) {
            Assert.notNull(fieldHolder.getFieldName(), "Field name cannot be null");

            if (!fieldHolder.getIsRelationId()) {
                criteria = getCriteriaEqByFieldWithCast(criteria, fieldHolder.getFieldName(), fieldHolder.getValue());
                continue;
            }

            criteria = getCriteriaAliasRelationIdWithCast(criteria, fieldHolder.getFieldName(), fieldHolder.getValue());
        }
        return criteria.list();
    }

    @Override
    public List<T> universalQuery(Map<String, List<?>> fields, QueryParams queryParams) {
        Criteria criteria = getCriteriaByProps(fields);

        if (queryParams.getSortBy() != null) {
            if (queryParams.getOrderType() == null || queryParams.getOrderType().equals(OrderType.ASC)) {
                criteria.addOrder(Order.asc(queryParams.getSortBy()));
            } else if (queryParams.getOrderType().equals(OrderType.DESC)) {
                criteria.addOrder(Order.desc(queryParams.getSortBy()));
            }
        }

        if (queryParams.getStart() != null)
            criteria.setFirstResult(queryParams.getStart());

        if (queryParams.getLimit() != null)
            criteria.setMaxResults(queryParams.getLimit());

        return criteria.list();
    }

    /**
     * Important! equals without cast type - do the cast yourself
     *
     * @param criteria criteria
     * @param fieldName fieldName
     * @param fieldValue fieldValue
     * @return Criteria
     */
    protected Criteria getCriteriaEqByField(Criteria criteria, String fieldName, Object fieldValue) {
        criteria.add(Restrictions.eq(fieldName, fieldValue));
        return criteria;
    }

    protected Criteria getCriteriaEqByFieldWithCast(Criteria criteria, String fieldName, Object fieldValue) {
        Object fieldValueCasted = fieldValue == null ? null : ReflectionUtils.castFieldValue(getPersistentClass(), fieldName, fieldValue);

        criteria.add(Restrictions.eq(fieldName, fieldValueCasted));
        return criteria;
    }

    protected Criteria getCriteriaAliasRelationIdWithCast(Criteria criteria, String fieldName, Object value) {
        Field field = ReflectionUtils.getField(getPersistentClass(), fieldName)
                .orElseThrow(() -> new IllegalArgumentException("Cannot find field name: '" + fieldName + "'"));

        Class<?> relationObjectClass = field.getType();
        Object castedId = ReflectionUtils.castFieldValue(relationObjectClass, "id", value);

        Criteria criteriaWithAlias = criteria.createAlias(fieldName, fieldName);
        return getCriteriaEqByField(criteriaWithAlias, fieldName + ".id", castedId);
    }
}