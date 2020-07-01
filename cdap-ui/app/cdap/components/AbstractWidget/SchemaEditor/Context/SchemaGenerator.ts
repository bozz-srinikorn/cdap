/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import {
  INode,
  IOrderedChildren,
} from 'components/AbstractWidget/SchemaEditor/Context/SchemaParser';
import { ISchemaType, IEnumFieldBase } from 'components/AbstractWidget/SchemaEditor/SchemaTypes';
import uuidV4 from 'uuid/v4';

const isTypeLogical = ({ type }) => {
  switch (type) {
    case 'decimal':
    case 'date':
    case 'time':
    case 'timestamp':
      return true;
    default:
      return false;
  }
};

const isComplexType = ({ type }) => {
  switch (type) {
    case 'record':
    case 'enum':
    case 'union':
    case 'map':
    case 'array':
      return true;
    default:
      return isTypeLogical({ type }) || false;
  }
};

function generateArrayType(children: IOrderedChildren, nullable: boolean) {
  const finalType = {
    type: 'array',
    items: null,
  };
  for (const childId of Object.keys(children)) {
    const currentChild = children[childId];
    const { type: childType, nullable: isArrayTypeNullable } = currentChild;
    const isArrayTypeComplex = isComplexType({ type: childType });
    if (!isArrayTypeComplex) {
      finalType.items = isArrayTypeNullable ? [childType, 'null'] : childType;
      continue;
    }
    // nested complex types.
    const complexType = generateSchemaFromComplexType(
      childType,
      currentChild,
      currentChild.nullable
    );
    if (complexType) {
      finalType.items = complexType;
    }
  }
  return nullable ? [finalType, 'null'] : finalType;
}

function generateMapType(children: IOrderedChildren, nullable) {
  const finalType = {
    type: 'map',
    keys: 'string',
    values: 'string',
  };
  for (const childId of Object.keys(children)) {
    const currentChild = children[childId];
    const { type, nullable: isCurrentChildNullable, internalType } = currentChild;
    const isMapChildComplexType = isComplexType({ type });
    if (!isMapChildComplexType) {
      if (internalType === 'map-keys-simple-type') {
        finalType.keys = isCurrentChildNullable ? [type, 'null'] : type;
      }
      if (internalType === 'map-values-simple-type') {
        finalType.values = isCurrentChildNullable ? [type, 'null'] : type;
      }
      continue;
    }
    // nested complex types.
    const complexType = generateSchemaFromComplexType(type, currentChild, isCurrentChildNullable);
    if (internalType === 'map-keys-complex-type-root') {
      finalType.keys = complexType as any;
    }
    if (internalType === 'map-values-complex-type-root') {
      finalType.values = complexType as any;
    }
  }
  return nullable ? [finalType, 'null'] : finalType;
}

function generateEnumType(children: IOrderedChildren, nullable) {
  const finalType: IEnumFieldBase = {
    type: 'enum',
    symbols: [],
  };
  if (Array.isArray(children.order)) {
    for (const childId of children.order) {
      const currentChild = children[childId];
      const { typeProperties } = currentChild;
      if (typeProperties.symbol && typeProperties.symbol !== '') {
        finalType.symbols.push(typeProperties.symbol);
      }
    }
  }
  return nullable ? [finalType, 'null'] : finalType;
}

function generateRecordType(children: IOrderedChildren, nullable: boolean) {
  const finalType = {
    type: 'record',
    name: `name-${uuidV4()}`,
    fields: [],
  };
  if (Array.isArray(children.order)) {
    for (const childId of children.order) {
      const currentChild = children[childId];
      const { name, type, nullable: isFiledNullable } = currentChild;
      if (!name || name === '') {
        continue;
      }
      const isFieldTypeComplex = isComplexType({ type });
      if (!isFieldTypeComplex) {
        finalType.fields.push({
          name,
          type: isFiledNullable ? [type, 'null'] : type,
        });
      } else {
        finalType.fields.push({
          name,
          type: generateSchemaFromComplexType(
            currentChild.type,
            currentChild,
            currentChild.nullable
          ),
        });
      }
    }
  }
  return nullable ? [finalType, 'null'] : finalType;
}

function generateUnionType(children: IOrderedChildren) {
  const finalType = [];
  if (Array.isArray(children.order)) {
    for (const childId of children.order) {
      const currentChild = children[childId];
      const { type } = currentChild;
      const isUnionTypeComplex = isComplexType({ type });
      if (!isUnionTypeComplex) {
        finalType.push(type);
        continue;
      }
      finalType.push(generateSchemaFromComplexType(type, currentChild, false));
    }
  }
  return finalType;
}

function generateLogicalType(child) {
  const { typeProperties, nullable } = child;
  return nullable ? [typeProperties, 'null'] : typeProperties;
}

function generateSchemaFromComplexType(type: string, currentChild, nullable: boolean) {
  const complexTypeChildren: IOrderedChildren = currentChild.children;
  switch (type) {
    case 'array':
      return generateArrayType(complexTypeChildren, nullable);
    case 'map':
      return generateMapType(complexTypeChildren, nullable);
    case 'enum':
      return generateEnumType(complexTypeChildren, nullable);
    case 'union':
      return generateUnionType(complexTypeChildren);
    case 'record':
      return generateRecordType(complexTypeChildren, nullable);
    case 'time':
    case 'timestamp':
    case 'decimal':
    case 'date':
      return generateLogicalType(currentChild);
    default:
      return type;
  }
}

/**
 * Utility to convert the entire schema tree to a valid avro schema JSON.
 * @param schemaTree Schema tree to convert to avro schema JSON.
 */
function SchemaGenerator(schemaTree: INode) {
  const avroSchema: ISchemaType = {
    name: 'etlSchemaBody',
    schema: {
      name: 'etlSchemaBody',
      type: 'record',
      fields: [],
    },
  };
  if (!schemaTree) {
    return avroSchema;
  }
  // Top level record fields.
  const { order } = schemaTree.children;
  if (Array.isArray(order)) {
    for (const id of order) {
      const currentField = schemaTree.children[id];
      const { name, type, nullable } = currentField;
      // Skip the newly added rows.
      if (!name || name === '') {
        continue;
      }
      const isFieldComplexType = isComplexType({ type });
      const field = {
        name,
        type: nullable ? [type, 'null'] : type,
      };
      if (isFieldComplexType) {
        field.type = generateSchemaFromComplexType(type, currentField, nullable);
      }
      avroSchema.schema.fields.push(field);
    }
  }
  return avroSchema;
}

export { SchemaGenerator, generateSchemaFromComplexType };
