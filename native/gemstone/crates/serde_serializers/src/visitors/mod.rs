use serde::de;

fn map_err<E, T>(value: Result<T, String>) -> Result<T, E>
where
    E: de::Error,
{
    value.map_err(de::Error::custom)
}

mod string_or_number;

pub(crate) use string_or_number::{StringOrNumberFromValue, StringOrNumberVisitor};
