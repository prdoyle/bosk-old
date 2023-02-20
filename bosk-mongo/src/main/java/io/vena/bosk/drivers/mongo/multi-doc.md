Describes a database format for a single-collection multi-document storage scheme.

## Design principles

### Manifest is the system of record for layout

A `MongoDriver` will accept a configuration describing the desired database layout.
However, if the database already exists, this is ignored, and the layout from the `manifest` document is used instead.
(In this way, the layout acts like the `initialState`.)

Any change to the `manifest` document probably triggers the bosk to be reloaded
the same way as for `initialState`.

### Scattering and gathering are done by mutating Bson objects

A single bosk update can correspond to multiple database writes,
and conversely, multiple change events can correspond to a single bosk update.
We refer to these as "scattering" and "gathering", respectively.

Scattering is done in these steps:
1. Serialize the bosk state into Bson as usual
2. Mutate the Bson to separate it into multiple Bsons based on the database layout
3. Write the objects in bottom-up order

Gathering is done in these steps:
1. Receive all the change events and buffer them, bottom-up, ending with the top Bson object
2. Mutate the top object to include all the other objects
3. Deserialize the Bson into bosk state as usual

### Limitations

There are some decisions we can make initially that will simplify the implementation.
We might find ways to overcome these limitations in the future.

#### Deleted nodes can be left behind

Suppose a Catalog is configured to use a separate collection, and it contains objects `A` and `B`.
Then an update arrives to replace that with `A` and `C`.
The driver is allowed to leave the `B` document intact.

Dangling documents can be cleared via `refurbish()` if desired.

An explicit `submitDeletion` shouldn't leave the document behind.

#### Complexity limits

Any or all of these limits might apply:
- Just one separate collection allowed
- The path of a separate collection may contain a limited number of parameters (perhaps none, or just one)

## Database contents

### Document `manifest`

Fields:
- `_id`: the string `manifest`
- `format`: an object with these fields:
  - `layout`: the string `multi-doc` to indicate the present specification is in effect
  - `version`: a three-segment version number `x.y.z` identifying the layout conventions
- `graftPoints`: array of percent-encoded paths where scattering/gathering should occur
	- Can be parameterized refs
    - Gathering should ideally not need to consult this; the state documents should be self-describing
- `echo`: used to implement `flush`
- `revision`: used to implement `flush`

### State documents

Fields:
- `_id`: percent-encoded concrete path where this document's state fits into the state tree. Starts with `/`
- `bsonPath`: array of field names indicating where the `state` would go if the bosk state were entirely in one big document
- `state`: the bosk state
- Any separate containers (Catalogs or SideTables) map IDs to the value `true` (rather than containing the actual tree node)
