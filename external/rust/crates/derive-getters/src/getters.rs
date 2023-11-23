//! Getters internals
use std::convert::TryFrom;

use proc_macro2::{TokenStream, Span};
use quote::quote;
use syn::{
    DeriveInput,
    FieldsNamed,
    Type,
    AttrStyle,
    Ident,
    LitStr,
    Result,
    Error,
    Attribute,
    parse::{Parse, ParseStream},
};

use crate::{
    extract::{named_fields, named_struct},
    faultmsg::Problem,
};

#[derive(Debug, Clone, PartialEq, Eq)]
enum Action {    
    Skip,
    Rename(Ident),
}

impl Parse for Action {
    fn parse(input: ParseStream) -> Result<Self> {
        syn::custom_keyword!(skip);
        syn::custom_keyword!(rename);
        
        if input.peek(skip) {
            let _ = input.parse::<skip>()?;
            if !input.is_empty() {
                Err(Error::new(Span::call_site(), Problem::TokensFollowSkip))
            } else {
                Ok(Action::Skip)
            }
        } else if input.peek(rename) {
            let _ = input.parse::<rename>()?;
            let _ = input.parse::<syn::Token![=]>()?;
            let name = input.parse::<LitStr>()?;
            if !input.is_empty() {
                Err(Error::new(Span::call_site(), Problem::TokensFollowNewName))
            } else {
                Ok(Action::Rename(Ident::new(name.value().as_str(), Span::call_site())))
            }
        } else {
            Err(Error::new(Span::call_site(), Problem::InvalidAttribute))
        }
    }
}

fn get_action_from(attributes: &[Attribute]) -> Result<Option<Action>> {
    let mut current: Option<Action> = None;
    
    for attr in attributes {
        if attr.style != AttrStyle::Outer { continue; }
        
        if attr.path.is_ident("getter") {
            current = Some(attr.parse_args::<Action>()?);
        }
    }
    
    Ok(current)
}

pub struct Field {
    ty: Type,    
    name: Ident,
    getter: Ident,
}

impl Field {
    fn from_field(field: &syn::Field) -> Result<Option<Self>> {
        let name: Ident =  field.ident
            .clone()
            .ok_or(Error::new(Span::call_site(), Problem::UnnamedField))?;
        
        match get_action_from(field.attrs.as_slice())? {
            Some(Action::Skip) => return Ok(None),
            Some(Action::Rename(ident)) => Ok(Some(Field {
                ty: field.ty.clone(),
                name: name,
                getter: ident,
            })),
            None => Ok(Some(Field {
                ty: field.ty.clone(),
                name: name.clone(),
                getter: name,
            })),
        }
    }
    
    fn from_fields_named(fields_named: &FieldsNamed) -> Result<Vec<Self>> {
        fields_named.named
            .iter()
            .try_fold(Vec::new(), |mut fields, field| {
                if let Some(field) = Field::from_field(field)? {
                    fields.push(field);
                }

                Ok(fields)
            })
    }

    fn emit(&self) -> TokenStream {
        let returns = &self.ty;
        let field_name = &self.name;
        let getter_name = &self.getter;
        
        match &self.ty {
            Type::Reference(tr) => {
                let lifetime = tr.lifetime.as_ref();
                quote!(
                    pub fn #getter_name(&#lifetime self) -> #returns {
                        self.#field_name
                    }
                )
            },
            _ => {
                quote!(
                    pub fn #getter_name(&self) -> &#returns {
                        &self.#field_name
                    }
                )
            },
        }
    }
}

pub struct NamedStruct<'a> {
    original: &'a DeriveInput,
    name: Ident,
    fields: Vec<Field>,
}

impl<'a> NamedStruct<'a> {
    pub fn emit(&self) -> TokenStream {
        let (impl_generics, struct_generics, where_clause) = self.original.generics
            .split_for_impl();        
        let struct_name = &self.name;
        let methods: Vec<TokenStream> = self.fields
            .iter()
            .map(|field| field.emit())
            .collect();

        quote!(
            impl #impl_generics #struct_name #struct_generics
                #where_clause
            {
                #(#methods)*
            }
        )        
    }
}

impl<'a> TryFrom<&'a DeriveInput> for NamedStruct<'a> {
    type Error = Error;
    
    fn try_from(node: &'a DeriveInput) -> Result<Self> {
        let struct_data = named_struct(node)?;
        let named_fields = named_fields(struct_data)?;
        let fields = Field::from_fields_named(named_fields)?;

        Ok(NamedStruct {
            original: node,
            name: node.ident.clone(),
            fields,
        })
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn parse_action() -> Result<()> {
        let a: Action = syn::parse_str("skip")?;
        assert!(a == Action::Skip);

        let r: Result<Action> = syn::parse_str("skip = blah");
        assert!(r.is_err());

        let a: Action = syn::parse_str("rename = \"hello\"")?;
        let check = Action::Rename(Ident::new("hello", Span::call_site()));
        assert!(a == check);

        let r: Result<Action> = syn::parse_str("rename + \"chooga\"");        
        assert!(r.is_err());

        let r: Result<Action> = syn::parse_str("rename = \"chooga\" | bongle");
        assert!(r.is_err());

        Ok(())
    }
}
