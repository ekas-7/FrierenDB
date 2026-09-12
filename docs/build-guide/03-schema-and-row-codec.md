# Step 3 - Schema, Rows and the Binary Row Codec

Package: `dev.frierendb.engine.schema`.

## Goal

Describe a table's columns, validate a row against them, and convert a row to and from bytes (the unit that storage will keep).

## Concepts

- **Schema**: ordered list of columns, each with a name, a type and a nullability flag.
- **Conforming a row**: checking arity, `NOT NULL` and types before storing.
- **Null bitmap**: one bit per column marks NULLs so NULLs cost no payload bytes.

## Design

### `Column`

```java
public record Column(String name, DataType type, boolean nullable)
```

The compact constructor rejects a blank name (`SchemaException`) and a null type. Helpers `Column.required(...)` and `Column.optional(...)`.

### `Row`

```java
public record Row(List<Value> values)
```

Immutable (`List.copyOf` in the compact constructor). Helpers `Row.of(Value...)`, `get(int)`, `size()`.

### `Schema`

- At least one column; **duplicate names are rejected case-insensitively**.
- `indexOf(name)` returns an `OptionalInt`; lookups lower-case the name (`Locale.ROOT`). `requireIndexOf` throws `SchemaException("Unknown column: ...")`.
- `conform(List<Value>)` returns a validated `Row`:
  - wrong number of values → `SchemaException`
  - `NULL` in a `NOT NULL` column → `SchemaException`
  - value type must equal the column type, **except** an `INT` is widened to a `DOUBLE` for a `DOUBLE` column
  - anything else → `SchemaException("Column x expects T but got U")`

### `RowCodec` format

```
[ null bitmap: ceil(n/8) bytes ][ value for each non-null column, in column order ]
```

| Type | Encoding |
|---|---|
| bitmap | bit `i % 8` of byte `i / 8` is 1 when column `i` is NULL |
| `INT` | 8 bytes, big-endian `long` (`ByteBuffer` default) |
| `DOUBLE` | 8 bytes, IEEE-754 `double` |
| `BOOLEAN` | 1 byte, 0 or 1 |
| `TEXT` | 4-byte length, then UTF-8 bytes |

Decoding needs the schema (the bytes are not self-describing). Decoding **verifies**: a bad text length, a truncated buffer, or trailing bytes throw `SchemaException("Corrupt row data...")`, so a damaged page cannot silently produce garbage rows.

## Build it

1. `SchemaException extends FrierenException` (with `serialVersionUID`).
2. `Column`, `Row`, `Schema` as above.
3. `RowCodec.encode(Schema, Row)`:
   1. `schema.conform(row.values())` first, so only valid rows are encoded.
   2. First pass: compute the bitmap and the payload size; encode text to UTF-8 once and keep the byte arrays.
   3. Allocate a `ByteBuffer` of `bitmap + payload`, write the bitmap, then each non-null value.
4. `RowCodec.decode(Schema, byte[])`:
   1. Read the bitmap, then for each column either `Value.NULL` or the value for the column's declared type.
   2. After the last column, fail if `buf.hasRemaining()`.
   3. Catch `BufferUnderflowException` and `IllegalArgumentException` and rethrow as `SchemaException("Corrupt row data")`.

Excerpt of the null bitmap logic:

```java
bitmap[i / 8] |= (byte) (1 << (i % 8));                 // encode
boolean isNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;  // decode
```

## Pitfalls

- **Text length must be validated** before allocating: reject `length < 0 || length > buf.remaining()` (`readText`), otherwise corrupt data can request a gigantic array.
- Encode the text bytes **once**; recomputing `getBytes` twice wastes time and risks a mismatch if the string changes.
- A wide schema (more than 8 columns) needs more than one bitmap byte; test with 20+ columns.
- Empty text is legal (length 0) and different from NULL.
- The row has no length of its own; the **heap file** stores the byte count (step 6-7).

## Tests to write

`SchemaTest`: case-insensitive lookup; duplicate names (ignoring case) rejected; empty schema rejected; blank column name rejected; unknown column error names the column.

`RowCodecTest`: round trip of all four types; round trip with NULLs; Unicode and empty text; a wide schema with many null bits; `INT` widened into a `DOUBLE` column; NULL in a required column rejected; wrong type rejected; wrong arity rejected; truncated bytes rejected; trailing bytes rejected; negative text length rejected.

## Checkpoint

Schema and codec tests pass. Quick manual check: encode `(1, 'Ada', NULL, 3.5)` and decode it back with the same schema; the decoded row equals the original.

Commit (together with step 2): `feat: add value types, schema and binary row codec`.
