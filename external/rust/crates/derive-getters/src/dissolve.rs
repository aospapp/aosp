//! Dissolve internals
use std::{
    iter::Extend,
    convert::TryFrom,
};

use proc_macro2::{TokenStream, Span};
use quote::quote;
use syn::{
    DeriveInput,
    FieldsNamed,
    Type,
    Ident,
    Result,
    Error,
    TypeTuple,
    AttrStyle,
    LitStr,
    Attribute,
    token::Paren,
    punctuated::Punctuated,
    parse::{Parse, ParseStream},
};

use crate::{
    extract::{named_fields, named_struct},
    faultmsg::Problem,
};

pub struct Field {
    ty: Type,    
    name: Ident,
}

impl Field {
    fn from_field(field: &syn::Field) -> Result<Self> {
        let name: Ident =  field.ident
            .clone()
            .ok_or(Error::new(Span::call_site(), Problem::UnnamedField))?;
        
        Ok(Field {
            ty: field.ty.clone(),
            name: name,
        })
    }
    
    fn from_fields_named(fields_named: &FieldsNamed) -> Result<Vec<Self>> {
        fields_named.named
            .iter()
            .map(|field| Field::from_field(field))
            .collect()
    }
}

struct Rename {
    name: Ident,
}

impl Parse for Rename {
    fn parse(input: ParseStream) -> Result<Self> {
        syn::custom_keyword!(rename);

        if input.peek(rename) {
            let _ = input.parse::<rename>()?;
            let _ = input.parse::<syn::Token![=]>()?;
            let name = input.parse::<LitStr>()?;
            if !input.is_empty() {
                Err(Error::new(Span::call_site(), Problem::TokensFollowNewName))
            } else {
                let name = Ident::new(name.value().as_str(), Span::call_site());
                Ok(Rename { name } )
            }
        } else {
            Err(Error::new(Span::call_site(), Problem::InvalidAttribute))
        }
    }
}

fn dissolve_rename_from(attributes: &[Attribute]) -> Result<Option<Ident>> {
    let mut current: Option<Ident> = None;

    for attr in attributes {
        if attr.style != AttrStyle::Outer { continue; }

        if attr.path.is_ident("dissolve") {
            let rename = attr.parse_args::<Rename>()?;
            current = Some(rename.name);
        }
    }

    Ok(current)
}

pub struct NamedStruct<'a> {
    original: &'a DeriveInput,
    name: Ident,
    fields: Vec<Field>,
    dissolve_rename: Option<Ident>,
}

impl<'a> NamedStruct<'a> {
    pub fn emit(&self) -> TokenStream {
        let (impl_generics, struct_generics, where_clause) = self.original.generics
            .split_for_impl();        
        let struct_name = &self.name;

        let types: Punctuated<Type, syn::Token![,]> = self.fields
            .iter()
            .fold(Punctuated::new(), |mut p, field| {
                p.push(field.ty.clone());
                p
            });

        let type_tuple = TypeTuple {
            paren_token: Paren { span: Span::call_site() },
            elems: types,
        };

        let fields: TokenStream = self.fields
            .iter()
            .enumerate()
            .fold(TokenStream::new(), |mut ts, (count, field)| {
                if count > 0 {
                    ts.extend(quote!(,))
                }
                
                let field_name = &field.name;
                let field_expr = quote!(
                    self.#field_name
                );

                ts.extend(field_expr);

                ts
            });

        let dissolve = Ident::new("dissolve", Span::call_site());
        let fn_name = self.dissolve_rename
            .as_ref()
            .unwrap_or(&dissolve);
        
        quote!(
            impl #impl_generics #struct_name #struct_generics
                #where_clause
            {
                pub fn #fn_name(self) -> #type_tuple {
                    (
                        #fields
                    )
                }
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
        let rename = dissolve_rename_from(node.attrs.as_slice())?;

        Ok(NamedStruct {
            original: node,
            name: node.ident.clone(),
            fields,
            dissolve_rename: rename,
        })
    }
}
