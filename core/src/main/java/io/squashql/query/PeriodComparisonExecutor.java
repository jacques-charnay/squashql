package io.squashql.query;

import static io.squashql.query.PeriodUnit.MONTH;
import static io.squashql.query.PeriodUnit.QUARTER;
import static io.squashql.query.PeriodUnit.SEMESTER;
import static io.squashql.query.PeriodUnit.YEAR;

import io.squashql.query.database.SQLTranslator;
import io.squashql.query.dto.Period;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap;
import org.eclipse.collections.api.map.primitive.ObjectIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

public class PeriodComparisonExecutor extends AComparisonExecutor {

  final ComparisonMeasureReferencePosition cmrp;

  public PeriodComparisonExecutor(ComparisonMeasureReferencePosition cmrp) {
    this.cmrp = cmrp;
  }

  public Map<Field, PeriodUnit> mapping(Period period) {
    if (period instanceof Period.Quarter q) {
      return Map.of(q.quarter(), QUARTER, q.year(), YEAR);
    } else if (period instanceof Period.Year y) {
      return Map.of(y.year(), YEAR);
    } else if (period instanceof Period.Month m) {
      return Map.of(m.month(), MONTH, m.year(), YEAR);
    } else if (period instanceof Period.Semester s) {
      return Map.of(s.semester(), SEMESTER, s.year(), YEAR);
    } else {
      throw new RuntimeException(period + " not supported yet");
    }
  }

  @Override
  protected BiPredicate<Object[], Header[]> createShiftProcedure(ComparisonMeasureReferencePosition cm, ObjectIntMap<String> indexByColumn) {
    Map<PeriodUnit, String> referencePosition = new HashMap<>();
    Period period = this.cmrp.period;
    Map<Field, PeriodUnit> mapping = mapping(period);
    MutableObjectIntMap<PeriodUnit> indexByPeriodUnit = new ObjectIntHashMap<>();
    for (Map.Entry<Field, String> entry : cm.referencePosition.entrySet()) {
      PeriodUnit pu = mapping.get(entry.getKey());
      referencePosition.put(pu, entry.getValue());
      indexByPeriodUnit.put(pu, indexByColumn.getIfAbsent(entry.getKey().name(), -1));
    }
    for (Map.Entry<Field, PeriodUnit> entry : mapping.entrySet()) {
      PeriodUnit pu = mapping.get(entry.getKey());
      referencePosition.putIfAbsent(pu, "c"); // constant for missing ref.
      indexByPeriodUnit.getIfAbsentPut(pu, indexByColumn.getIfAbsent(entry.getKey().name(), -1));
    }
    return new ShiftProcedure(period, referencePosition, indexByPeriodUnit);
  }

  static class ShiftProcedure implements BiPredicate<Object[], Header[]> {

    final Period period;
    final Map<PeriodUnit, String> referencePosition;
    final ObjectIntMap<PeriodUnit> indexByPeriodUnit;
    final Map<PeriodUnit, Object> transformationByPeriodUnit;

    ShiftProcedure(Period period,
                   Map<PeriodUnit, String> referencePosition,
                   ObjectIntMap<PeriodUnit> indexByPeriodUnit) {
      this.period = period;
      this.referencePosition = referencePosition;
      this.indexByPeriodUnit = indexByPeriodUnit;
      PeriodUnit[] periodUnits = getPeriodUnits(period);
      this.transformationByPeriodUnit = new HashMap<>();
      for (int i = 0; i < periodUnits.length; i++) {
        Object parse = parse(referencePosition.get(periodUnits[i]));
        if (parse != null) {
          this.transformationByPeriodUnit.put(periodUnits[i], parse);
        }
      }
    }

    @Override
    public boolean test(Object[] row, Header[] headers) {
      int unknown = -1;
      int yearIndex = this.indexByPeriodUnit.getIfAbsent(PeriodUnit.YEAR, unknown);
      int semesterIndex = this.indexByPeriodUnit.getIfAbsent(PeriodUnit.SEMESTER, unknown);
      int quarterIndex = this.indexByPeriodUnit.getIfAbsent(PeriodUnit.QUARTER, unknown);
      int monthIndex = this.indexByPeriodUnit.getIfAbsent(PeriodUnit.MONTH, unknown);
      Object yearTransformation = this.transformationByPeriodUnit.get(PeriodUnit.YEAR);
      Object semesterTransformation = this.transformationByPeriodUnit.get(PeriodUnit.SEMESTER);
      Object quarterTransformation = this.transformationByPeriodUnit.get(PeriodUnit.QUARTER);
      Object monthTransformation = this.transformationByPeriodUnit.get(PeriodUnit.MONTH);
      if (this.period instanceof Period.Quarter) {
        // YEAR, QUARTER
        if (this.referencePosition.containsKey(PeriodUnit.YEAR) && yearTransformation != null) {
          int year = readAsLong(row[yearIndex]);
          if (year < 0) {
            return false;
          }
          write(row, yearIndex, headers[yearIndex], year + (int) yearTransformation);
        }
        if (this.referencePosition.containsKey(PeriodUnit.QUARTER) && quarterTransformation != null) {
          int quarter = readAsLong(row[quarterIndex]);
          if (quarter < 0) {
            return false;
          }
          LocalDate d = LocalDate.of(readAsLong(row[yearIndex]), quarter * 3, 1);
          LocalDate newDate = d.plusMonths(((int) quarterTransformation) * 3);
          write(row, quarterIndex, headers[quarterIndex], (int) IsoFields.QUARTER_OF_YEAR.getFrom(newDate));
          write(row, yearIndex, headers[yearIndex], newDate.getYear());// year might have changed
        }
      } else if (this.period instanceof Period.Year) {
        // YEAR
        if (this.referencePosition.containsKey(PeriodUnit.YEAR) && yearTransformation != null) {
          int year = readAsLong(row[yearIndex]);
          if (year < 0) {
            return false;
          }
          write(row, yearIndex, headers[yearIndex], year + (int) yearTransformation);
        }
      } else if (this.period instanceof Period.Month) {
        // YEAR, MONTH
        if (this.referencePosition.containsKey(PeriodUnit.YEAR) && yearTransformation != null) {
          int year = readAsLong(row[yearIndex]);
          if (year < 0) {
            return false;
          }
          write(row, yearIndex, headers[yearIndex], year + (int) yearTransformation);
        }
        if (this.referencePosition.containsKey(PeriodUnit.MONTH) && monthTransformation != null) {
          int month = readAsLong(row[monthIndex]);
          if (month < 0) {
            return false;
          }
          LocalDate newDate = LocalDate.of(readAsLong(row[yearIndex]), month, 1)
                  .plusMonths((int) monthTransformation);
          write(row, monthIndex, headers[monthIndex], newDate.getMonthValue());
          write(row, yearIndex, headers[yearIndex], newDate.getYear()); // year might have changed
        }
      } else if (this.period instanceof Period.Semester) {
        // YEAR, SEMESTER
        if (this.referencePosition.containsKey(PeriodUnit.YEAR) && yearTransformation != null) {
          int year = readAsLong(row[yearIndex]);
          if (year < 0) {
            return false;
          }
          write(row, yearIndex, headers[yearIndex], year + (int) yearTransformation);
        }
        if (this.referencePosition.containsKey(PeriodUnit.SEMESTER) && semesterTransformation != null) {
          int semester = readAsLong(row[semesterIndex]);
          if (semester < 0) {
            return false;
          }
          LocalDate d = LocalDate.of(readAsLong(row[yearIndex]), semester * 6, 1);
          LocalDate newDate = d.plusMonths(((int) semesterTransformation) * 6);
          write(row, semesterIndex, headers[semesterIndex], newDate.getMonthValue() / 6);
          write(row, yearIndex, headers[yearIndex], newDate.getYear()); // year might have changed
        }
      } else {
        throw new RuntimeException(this.period + " not supported yet");
      }
      return true;
    }

    private static int readAsLong(Object o) {
      if (SQLTranslator.TOTAL_CELL.equals(o)) {
        return -1;
      }
      return (int) ((Number) o).longValue(); // with some database, year could be Long object.
    }

    private static void write(Object[] rowIndex, int index, Header header, int number) {
      if (header.type() == Long.class || header.type() == long.class) {
        rowIndex[index] = (long) number;
      } else if (header.type() == Integer.class || header.type() == int.class) {
        rowIndex[index] = number;
      } else {
        throw new IllegalArgumentException("Unsupported type " + header.type());
      }
    }

    private static PeriodUnit[] getPeriodUnits(Period period) {
      if (period instanceof Period.Quarter) {
        return new PeriodUnit[]{PeriodUnit.YEAR, PeriodUnit.QUARTER};
      } else if (period instanceof Period.Year) {
        return new PeriodUnit[]{PeriodUnit.YEAR};
      } else if (period instanceof Period.Month) {
        return new PeriodUnit[]{PeriodUnit.YEAR, PeriodUnit.MONTH};
      } else if (period instanceof Period.Semester) {
        return new PeriodUnit[]{PeriodUnit.YEAR, PeriodUnit.SEMESTER};
      } else {
        throw new RuntimeException(period + " not supported yet");
      }
    }
  }
}
