# Accessors

## Accessor: ":."
> Used for field access, if identifiers to an objects method, includes
an implicit self of the object it was called on. If identifying a field
can only be used to access instance fields if called on an object, all
static fields must be called directly on the type or namespace.

## Accessor: "::"
> Used for function access/calls. while instance functions with an implicit 
self can be accessed with ":." . All calls to functions, whether on an instance 
or a type, must be called with "::" . When '::' is called on an instance, an implicit
 self is passed to the method. Static functions cannot be accessed on instance types.


>  INSTANCE ACCESS
> 
> instance:.method → Instance method with implicit self captured
> 
> instance::method[] → Direct call with implicit self (instance methods only)
> 
> instance::method → Raw method expecting self (instance methods only)
> 
> instance:.field → Instance Fields only

> TYPE ACCESS
> 
> Type::method[] → Direct call (static methods only, no self bound)
> 
> Type::method → Raw method reference (static methods only)
> 
> Type:.field  → Static Fields Only