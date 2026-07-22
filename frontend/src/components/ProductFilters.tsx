import { Box, TextField, MenuItem, InputAdornment, Stack } from '@mui/material';
import { SearchOutlined } from '@mui/icons-material';
import type { Sport, Category, Brand } from '@/types/catalog';

interface ProductFiltersProps {
  search: string;
  onSearchChange: (v: string) => void;
  sportId: string;
  onSportChange: (v: string) => void;
  categoryId: string;
  onCategoryChange: (v: string) => void;
  brandId: string;
  onBrandChange: (v: string) => void;
  sort: string;
  onSortChange: (v: string) => void;
  sports: Sport[];
  categories: Category[];
  brands: Brand[];
}

const SORT_OPTIONS = [
  { value: 'newest', label: 'Newest' },
  { value: 'price_asc', label: 'Price: Low to High' },
  { value: 'price_desc', label: 'Price: High to Low' },
];

const ProductFilters = ({
  search,
  onSearchChange,
  sportId,
  onSportChange,
  categoryId,
  onCategoryChange,
  brandId,
  onBrandChange,
  sort,
  onSortChange,
  sports,
  categories,
  brands,
}: ProductFiltersProps) => (
  <Stack
    direction={{ xs: 'column', sm: 'row' }}
    spacing={2}
    sx={{ mb: 3, flexWrap: 'wrap' }}
  >
    <TextField
      placeholder="Search products..."
      value={search}
      onChange={(e) => onSearchChange(e.target.value)}
      size="small"
      sx={{ minWidth: 220, flexGrow: 1 }}
      slotProps={{
        input: {
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined fontSize="small" />
            </InputAdornment>
          ),
        },
      }}
    />
    <TextField
      select
      label="Sport"
      value={sportId}
      onChange={(e) => onSportChange(e.target.value)}
      size="small"
      sx={{ minWidth: 140 }}
    >
      <MenuItem value="">All Sports</MenuItem>
      {sports.map((s) => (
        <MenuItem key={s.id} value={s.id}>
          {s.name}
        </MenuItem>
      ))}
    </TextField>
    <TextField
      select
      label="Category"
      value={categoryId}
      onChange={(e) => onCategoryChange(e.target.value)}
      size="small"
      sx={{ minWidth: 140 }}
    >
      <MenuItem value="">All Categories</MenuItem>
      {categories.map((c) => (
        <MenuItem key={c.id} value={c.id}>
          {c.name}
        </MenuItem>
      ))}
    </TextField>
    <TextField
      select
      label="Brand"
      value={brandId}
      onChange={(e) => onBrandChange(e.target.value)}
      size="small"
      sx={{ minWidth: 140 }}
    >
      <MenuItem value="">All Brands</MenuItem>
      {brands.map((b) => (
        <MenuItem key={b.id} value={b.id}>
          {b.name}
        </MenuItem>
      ))}
    </TextField>
    <TextField
      select
      label="Sort"
      value={sort}
      onChange={(e) => onSortChange(e.target.value)}
      size="small"
      sx={{ minWidth: 160 }}
    >
      {SORT_OPTIONS.map((o) => (
        <MenuItem key={o.value} value={o.value}>
          {o.label}
        </MenuItem>
      ))}
    </TextField>
  </Stack>
);

export default ProductFilters;
